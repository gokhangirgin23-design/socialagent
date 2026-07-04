package com.api.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.config.AppProperties;
import com.api.config.CreditCatalog;
import com.api.dto.ContentCreateRequest;
import com.api.dto.ContentCreateResponse;
import com.api.dto.ContentEditRequest;
import com.api.dto.ContentRequestDto;
import com.api.dto.repository.ContentRequestRepository;
import com.api.entity.ContentRequest;
import com.api.entity.ContentRequestStatus;
import com.api.entity.ContentType;
import com.api.messaging.ContentQueueProducer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * İçerik üretim isteği iş mantığı.
 * Rapor bazlı görsel + caption üretim isteklerini yönetir.
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentRequestService {

    private final ContentRequestRepository contentRequestRepository;
    private final ContentQueueProducer contentQueueProducer;
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;
    private final PaymentService paymentService;
    private final S3UploadService s3UploadService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============================================================
    // Create
    // ============================================================

    /**
     * Kullanılabilir içerik tiplerini, kredi maliyetlerini ve kullanıcının kredi bakiyesini döner.
     * Endpoint: POST /content/available-types
     */
    public Map<String, Object> availableTypes(UUID userId) {
        long creditBalance = paymentService.getCreditBalance(userId);
        List<Map<String, Object>> types = List.of(
                typeEntry("POST",     "Post",      CreditCatalog.POST_CREDIT_COST),
                typeEntry("STORY",    "Story",     CreditCatalog.STORY_CREDIT_COST),
                typeEntry("CAROUSEL", "Carousel",  CreditCatalog.CAROUSEL_CREDIT_COST),
                typeEntry("REEL",     "Reel",      null)
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("creditBalance", creditBalance);
        result.put("types", types);
        return result;
    }

    private Map<String, Object> typeEntry(String value, String label, Integer creditCost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", value);
        m.put("label", label);
        m.put("creditCost", creditCost);
        return m;
    }

    /**
     * Yeni içerik üretim isteği oluşturur ve kuyruğa basar.
     * Ödeme aktifse kredi bakiyesi kontrolü yapılır.
     *
     * @return ContentCreateResponse (QUEUED veya INSUFFICIENT)
     */
    public ContentCreateResponse create(UUID userId, ContentCreateRequest request) {
        // contentType validasyonu
        ContentType contentType = parseContentType(request.getContentType());
        if (contentType == ContentType.REEL) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR, "REEL içerik üretimi şu an kullanılamıyor.");
        }
        // Yazılı görsel üretimi şimdilik kapalı (maliyet stratejisi) — ileride tek satır silinerek açılır
        if (request.isIncludeTextInVisual()) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "Görsel üzerine yazı ekleme özelliği şu an kullanılamıyor.");
        }

        // Kredi bakiyesi kontrolü (PAYMENT_ENABLED = true ise)
        int creditCost = CreditCatalog.creditCostFor(contentType);
        if (appProperties.getPayment().isEnabled()) {
            long creditBalance = paymentService.getCreditBalance(userId);
            if (creditBalance < creditCost) {
                log.info("Yetersiz kredi: userId={}, contentType={}, creditCost={}, creditBalance={}",
                        userId, contentType, creditCost, creditBalance);
                return ContentCreateResponse.insufficient(creditCost, creditBalance);
            }
        }

        // Rapor kullanıcıya ait mi?
        validateReportOwnership(userId, request.getReportId());

        ContentRequest entity = new ContentRequest();
        entity.setContentRequestId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setReportId(request.getReportId());
        entity.setContentType(contentType);
        // base64 data URL ise S3'e yükle; DB'de sadece S3 URL saklansın (base64 asla yazılmaz)
        entity.setProductImageUrl(uploadProductImageIfPresent(request.getProductImageUrl(), userId, entity.getContentRequestId()));
        entity.setIncludeTextInVisual(request.isIncludeTextInVisual());
        entity.setStatus(ContentRequestStatus.PENDING);
        entity.setEditCount(0);
        entity.setAttemptCount(0);
        entity.setActive((short) 1);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedDate(now);
        entity.setUpdatedDate(now);

        contentRequestRepository.save(entity);
        contentQueueProducer.publish(entity.getContentRequestId());

        log.info("İçerik isteği oluşturuldu: id={}, userId={}, reportId={}, type={}, creditCost={}",
                entity.getContentRequestId(), userId, request.getReportId(), contentType, creditCost);

        return ContentCreateResponse.queued(entity.getContentRequestId(), creditCost);
    }

    // ============================================================
    // Edit
    // ============================================================

    /**
     * Mevcut isteği düzenleme talimatıyla yeniden kuyruğa basar.
     * edit_count >= editLimit ise hata döner.
     */
    public void edit(UUID userId, ContentEditRequest request) {
        ContentRequest entity = loadOwned(userId, request.getContentRequestId());

        int editLimit = appProperties.getContent().getEditLimit();
        if (entity.getEditCount() >= editLimit) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "Düzenleme hakkınız dolmuştur (maksimum " + editLimit + ").");
        }

        // Eski görsel + metadata KORUNUR — yeni üretim başarılı olursa üzerine yazar,
        // başarısız olursa kullanıcı bir önceki başarılı içeriği görmeye devam eder.
        entity.setEditInstruction(request.getEditInstruction());
        entity.setEditCount(entity.getEditCount() + 1);
        entity.setStatus(ContentRequestStatus.PENDING);
        entity.setProcessError(null);
        entity.setUpdatedDate(LocalDateTime.now());

        contentRequestRepository.save(entity);
        contentQueueProducer.publish(entity.getContentRequestId());

        log.info("İçerik isteği yeniden kuyruğa basıldı: id={}, editCount={}",
                entity.getContentRequestId(), entity.getEditCount());
    }

    // ============================================================
    // List / Detail
    // ============================================================

    /**
     * Kullanıcının içerik isteklerini sayfalı listeler (en yeni önce).
     */
    public List<ContentRequestDto> list(UUID userId, int page, int size) {
        int offset = page * size;
        // visual_urls ve product_image_url base64 data URL içerebilir (MB boyutunda) —
        // liste görünümü için gereksiz, sadece detail endpoint dönsün
        String sql = """
                SELECT content_request_id, report_id, content_type, status,
                       include_text_in_visual, edit_instruction,
                       edit_count, caption,
                       created_date, process_finished_date, process_error
                FROM content_request
                WHERE user_id = ? AND active = 1
                ORDER BY created_date DESC
                LIMIT ? OFFSET ?
                """;
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> mapListRow(rs), userId, size, offset);
        } catch (Exception ex) {
            log.error("İçerik listesi yüklenemedi: userId={}, hata={}", userId, ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Tek bir içerik isteğinin detayını döner; sahiplik korumalı.
     */
    public ContentRequestDto detail(UUID userId, UUID contentRequestId) {
        String sql = """
                SELECT content_request_id, report_id, content_type, status,
                       product_image_url, include_text_in_visual, edit_instruction,
                       edit_count, visual_urls, caption, hashtags, cta,
                       first_comment, suggested_post_time,
                       created_date, process_finished_date, process_error
                FROM content_request
                WHERE content_request_id = ? AND user_id = ? AND active = 1
                """;
        List<ContentRequestDto> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs),
                contentRequestId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    private ContentRequest loadOwned(UUID userId, UUID contentRequestId) {
        ContentRequest entity = contentRequestRepository.findById(contentRequestId).orElse(null);
        if (entity == null || entity.getActive() == 0) {
            throw new ApiException(ResponseCode.NOT_FOUND, "İçerik isteği bulunamadı.");
        }
        if (!entity.getUserId().equals(userId)) {
            throw new ApiException(ResponseCode.UNAUTHORIZED, "Bu içerik isteğine erişim yetkiniz yok.");
        }
        return entity;
    }

    private void validateReportOwnership(UUID userId, UUID reportId) {
        String sql = """
                SELECT COUNT(*) FROM report r
                JOIN report_request rr ON rr.request_id = r.request_id
                WHERE r.report_id = ? AND rr.user_id = ?
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, reportId, userId);
        if (count == null || count == 0) {
            throw new ApiException(ResponseCode.NOT_FOUND, "Rapor bulunamadı.");
        }
    }

    private ContentType parseContentType(String value) {
        try {
            return ContentType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "Geçersiz contentType. Kabul edilenler: POST, STORY, CAROUSEL, REEL");
        }
    }

    /** Liste için hafif mapper — büyük alanlar (visual_urls, product_image_url) dahil değil. */
    private ContentRequestDto mapListRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        ContentRequestDto dto = new ContentRequestDto();
        dto.setContentRequestId((UUID) rs.getObject("content_request_id"));
        dto.setReportId((UUID) rs.getObject("report_id"));
        dto.setContentType(rs.getString("content_type"));
        dto.setStatus(rs.getString("status"));
        dto.setIncludeTextInVisual(rs.getBoolean("include_text_in_visual"));
        dto.setEditInstruction(rs.getString("edit_instruction"));
        dto.setEditCount(rs.getInt("edit_count"));
        dto.setEditLimit(appProperties.getContent().getEditLimit());
        dto.setCaption(rs.getString("caption"));
        dto.setProcessError(rs.getString("process_error"));
        java.sql.Timestamp created = rs.getTimestamp("created_date");
        if (created != null) dto.setCreatedDate(created.toLocalDateTime());
        java.sql.Timestamp finished = rs.getTimestamp("process_finished_date");
        if (finished != null) dto.setProcessFinishedDate(finished.toLocalDateTime());
        return dto;
    }

    /** Detail için tam mapper — tüm alanlar dahil. */
    private ContentRequestDto mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        ContentRequestDto dto = mapListRow(rs);
        dto.setProductImageUrl(rs.getString("product_image_url"));
        dto.setHashtags(rs.getString("hashtags"));
        dto.setCta(rs.getString("cta"));
        dto.setFirstComment(rs.getString("first_comment"));
        dto.setSuggestedPostTime(rs.getString("suggested_post_time"));
        dto.setVisualUrls(parseVisualUrls(rs.getString("visual_urls")));
        return dto;
    }

    /**
     * Ürün görseli base64 data URL ise S3'e yükleyip URL döner.
     * S3 kapalıysa veya dönüştürme başarısızsa null döner — base64 asla DB'ye yazılmaz.
     */
    private String uploadProductImageIfPresent(String rawUrl, UUID userId, UUID contentRequestId) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        if (!rawUrl.startsWith("data:")) {
            // Zaten HTTP URL ise direkt kullan (tekrar yükleme yok)
            return rawUrl;
        }
        try {
            int comma = rawUrl.indexOf(',');
            if (comma < 0) return null;
            byte[] imgBytes = Base64.getDecoder().decode(rawUrl.substring(comma + 1));
            String s3Url = s3UploadService.uploadProductImage(imgBytes, userId, contentRequestId);
            if (s3Url == null) {
                log.info("Ürün görseli S3'e yüklenemedi; product_image_url null bırakıldı: contentRequestId={}", contentRequestId);
            }
            return s3Url;
        } catch (Exception ex) {
            log.warn("Ürün görseli dönüştürme hatası; product_image_url null: hata={}", ex.getMessage());
            return null;
        }
    }

    private List<String> parseVisualUrls(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> urls = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            // S3 URL'lerini pre-signed URL'e çevir (private bucket erişimi için)
            return urls.stream().map(s3UploadService::presign).collect(java.util.stream.Collectors.toList());
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }
}
