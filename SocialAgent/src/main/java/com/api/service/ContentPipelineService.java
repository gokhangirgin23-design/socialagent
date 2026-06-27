package com.api.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import com.api.ai.AiAnalysisService;
import com.api.ai.ContentPrompts;
import com.api.ai.GeminiImageService;
import com.api.ai.SoraVideoService;
import com.api.config.AppProperties;
import com.api.dto.repository.ContentRequestRepository;
import com.api.entity.ContentRequest;
import com.api.entity.ContentRequestStatus;
import com.api.entity.ContentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * İçerik üretim pipeline'ı (görsel + caption + S3).
 * ContentWorker bu servisi çağırır; @Transactional DEĞİL (dış API çağrıları uzun sürebilir).
 * Status güncellemeleri bağımsız auto-commit'tir (ScrapePipelineService ile aynı felsefe).
 *
 * Akış:
 *   1) content_request yükle → PROCESSING
 *   2) Rapor içeriğini yükle (report.report_content)
 *   3) Brand DNA üret / önbellekten al (OpenAI)
 *   4) Görsel üret (Gemini Image) → S3'e yükle
 *   5) Caption + hashtag + CTA üret (OpenAI)
 *   6) Kaydet → COMPLETED veya FAILED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentPipelineService {

    private final ContentRequestRepository contentRequestRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AiAnalysisService aiAnalysisService;
    private final GeminiImageService geminiImageService;
    private final SoraVideoService soraVideoService;
    private final S3UploadService s3UploadService;
    private final ContentRequestService contentRequestService;
    private final PaymentService paymentService;
    private final AppProperties appProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Tek bir içerik isteğini baştan sona işler.
     */
    public void process(UUID contentRequestId) {
        ContentRequest req = contentRequestRepository.findById(contentRequestId).orElse(null);
        if (req == null || req.getActive() == 0) {
            log.warn("Content request bulunamadı veya pasif: id={}", contentRequestId);
            return;
        }

        markProcessing(req);

        try {
            String reportContent = loadReportContent(req.getReportId());
            if (reportContent == null || reportContent.isBlank()) {
                log.warn("Rapor içeriği bulunamadı: reportId={}", req.getReportId());
                markFinished(req, ContentRequestStatus.FAILED, "Rapor içeriği bulunamadı");
                return;
            }

            // Brand DNA: önbellekte varsa kullan, yoksa üret
            String brandDna = req.getBrandDnaJson();
            if (brandDna == null || brandDna.isBlank()) {
                brandDna = generateBrandDna(req, reportContent);
                if (brandDna != null) {
                    req.setBrandDnaJson(brandDna);
                    saveQuiet(req);
                }
            }

            // Görsel üretim + S3 yükleme
            VisualResult visual = generateAndUploadVisuals(req, brandDna, reportContent);

            // Görsel/video üretim servisi aktifken üretim başarısızsa FAILED — bakiye düşülmez
            boolean imageServiceActive = req.getContentType() == ContentType.REEL
                    ? soraVideoService.isActive()
                    : geminiImageService.isActive();
            if (imageServiceActive && visual.anyFailed()) {
                String err = "Görsel üretimi başarısız oldu (" + visual.failCount() + "/" + visual.expected() + " görsel üretilemedi)";
                log.warn("İçerik FAILED: contentRequestId={}, sebep={}", contentRequestId, err);
                markFinished(req, ContentRequestStatus.FAILED, err);
                return;
            }

            req.setVisualUrls(toJsonArray(visual.urls()));

            // Caption + hashtag + CTA
            applyContentMetadata(req, brandDna, reportContent);

            markFinished(req, ContentRequestStatus.COMPLETED, null);
            log.info("İçerik üretimi tamamlandı: contentRequestId={}", contentRequestId);

            // Ödeme: yalnızca COMPLETED olunca bakiyeyi düş (hata pipeline'ı bozmaz)
            if (appProperties.getPayment().isEnabled()) {
                try {
                    BigDecimal price = contentRequestService.priceFor(req.getContentType());
                    paymentService.tryDebit(req.getUserId(), price, req.getContentRequestId());
                } catch (Exception ex) {
                    log.warn("İçerik ödeme düşümü başarısız (üretim etkilenmez): id={}, hata={}",
                            contentRequestId, ex.getMessage());
                }
            }

        } catch (Exception ex) {
            log.error("İçerik üretimi başarısız: contentRequestId={}, hata={}", contentRequestId, ex.getMessage(), ex);
            markFinished(req, ContentRequestStatus.FAILED, ex.getMessage());
        }
    }

    // ============================================================
    // Brand DNA
    // ============================================================

    private String generateBrandDna(ContentRequest req, String reportContent) {
        // Kullanıcının kendi hesabına ait son 10 post caption'ını al
        String postsContext = loadOwnPostsCaptions(req.getReportId());
        String prompt = ContentPrompts.forBrandDna(postsContext, reportContent);
        String dna = aiAnalysisService.generateBrandDna(prompt);
        if (dna != null) {
            log.info("Brand DNA üretildi: contentRequestId={}", req.getContentRequestId());
        } else {
            log.info("Brand DNA üretilemedi (AI kapalı veya hata); atlanıyor: contentRequestId={}",
                    req.getContentRequestId());
        }
        return dna;
    }

    private String loadOwnPostsCaptions(UUID reportId) {
        // report_id → report.request_id → social_post (OWN) son 10 post caption'ı
        String sql = """
                SELECT sp.caption
                FROM social_post sp
                JOIN report r ON r.request_id = sp.request_id
                WHERE r.report_id = ?
                  AND sp.source_type = 'OWN'
                  AND sp.caption IS NOT NULL
                ORDER BY sp.post_date DESC
                LIMIT 10
                """;
        try {
            List<String> captions = jdbcTemplate.queryForList(sql, String.class, reportId);
            if (captions.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < captions.size(); i++) {
                sb.append("Post ").append(i + 1).append(": ").append(captions.get(i)).append("\n");
            }
            return sb.toString();
        } catch (Exception ex) {
            log.warn("OWN post caption'ları yüklenemedi: hata={}", ex.getMessage());
            return null;
        }
    }

    // ============================================================
    // Görsel üretim + S3
    // ============================================================

    private record VisualResult(List<String> urls, int expected) {
        boolean anyFailed() { return urls.size() < expected; }
        int failCount()     { return expected - urls.size(); }
    }

    private VisualResult generateAndUploadVisuals(ContentRequest req, String brandDna, String reportContent) {
        ContentType type = req.getContentType();
        List<String> urls = new ArrayList<>();

        if (type == ContentType.CAROUSEL) {
            String[] roles = {"HOOK", "CONTENT", "CTA"};
            for (int i = 0; i < roles.length; i++) {
                String prompt = ContentPrompts.forVisual(brandDna, reportContent, "CAROUSEL", i, roles[i],
                        req.isIncludeTextInVisual());
                String url = generateAndUpload(req, prompt, i);
                if (url != null) urls.add(url);
            }
            return new VisualResult(urls, roles.length);
        } else if (type == ContentType.REEL) {
            String prompt = ContentPrompts.forVideo(brandDna, reportContent);
            String url = generateAndUploadVideo(req, prompt);
            if (url != null) urls.add(url);
            return new VisualResult(urls, 1);
        } else {
            String prompt = ContentPrompts.forVisual(brandDna, reportContent, type.name(), 0, null,
                    req.isIncludeTextInVisual());
            String url = generateAndUpload(req, prompt, 0);
            if (url != null) urls.add(url);
            return new VisualResult(urls, 1);
        }
    }

    private String generateAndUploadVideo(ContentRequest req, String prompt) {
        byte[] videoBytes = soraVideoService.generateVideo(prompt);
        if (videoBytes == null) {
            // Sora pasifse Gemini ile statik görsel fallback
            if (!soraVideoService.isActive()) {
                log.info("Sora pasif; REEL için Gemini fallback: contentRequestId={}", req.getContentRequestId());
                String imagePrompt = ContentPrompts.forVisual(null, null, "REEL", 0, null, false);
                return generateAndUpload(req, imagePrompt, 0);
            }
            log.warn("Sora video üretilemedi: contentRequestId={}", req.getContentRequestId());
            return null;
        }
        return s3UploadService.uploadVideo(videoBytes, req.getUserId(), req.getContentRequestId());
    }

    private String generateAndUpload(ContentRequest req, String prompt, int index) {
        byte[] imageBytes = geminiImageService.generateImage(prompt, req.getProductImageUrl());
        if (imageBytes == null) {
            log.warn("Görsel üretilemedi: contentRequestId={}, index={}", req.getContentRequestId(), index);
            return null;
        }
        return s3UploadService.upload(imageBytes, req.getUserId(), req.getContentRequestId(), index);
    }

    // ============================================================
    // Caption + metadata
    // ============================================================

    private void applyContentMetadata(ContentRequest req, String brandDna, String reportContent) {
        String reportSnippet = reportContent.length() > 800
                ? reportContent.substring(0, 800) + "..."
                : reportContent;
        String prompt = ContentPrompts.forContentMetadata(brandDna, reportSnippet, req.getContentType().name());
        String metadataJson = aiAnalysisService.generateContentMetadata(prompt);
        if (metadataJson == null) {
            log.info("Content metadata üretilemedi; atlanıyor: contentRequestId={}", req.getContentRequestId());
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            req.setCaption(textOrNull(node, "caption"));
            req.setHashtags(textOrNull(node, "hashtags"));
            req.setCta(textOrNull(node, "cta"));
            req.setFirstComment(textOrNull(node, "firstComment"));
            req.setSuggestedPostTime(textOrNull(node, "suggestedPostTime"));
        } catch (Exception ex) {
            log.warn("Content metadata JSON ayrıştırılamadı; ham metin caption'a yazılıyor: hata={}", ex.getMessage());
            req.setCaption(metadataJson);
        }
    }

    // ============================================================
    // Rapor içeriği yükleme
    // ============================================================

    private String loadReportContent(UUID reportId) {
        String sql = "SELECT report_content FROM report WHERE report_id = ?";
        try {
            List<String> rows = jdbcTemplate.queryForList(sql, String.class, reportId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception ex) {
            log.error("Rapor içeriği yüklenemedi: reportId={}, hata={}", reportId, ex.getMessage());
            return null;
        }
    }

    // ============================================================
    // Status yönetimi (bağımsız DB yazımları — @Transactional yok)
    // ============================================================

    private void markProcessing(ContentRequest req) {
        String sql = """
                UPDATE content_request
                SET status = 'PROCESSING', process_started_date = ?, updated_date = ?
                WHERE content_request_id = ?
                """;
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(sql, now, now, req.getContentRequestId());
        req.setStatus(ContentRequestStatus.PROCESSING);
        req.setProcessStartedDate(now);
    }

    private void markFinished(ContentRequest req, ContentRequestStatus status, String error) {
        LocalDateTime now = LocalDateTime.now();
        req.setStatus(status);
        req.setProcessFinishedDate(now);
        req.setProcessError(error);
        req.setUpdatedDate(now);
        contentRequestRepository.save(req);
    }

    private void saveQuiet(ContentRequest req) {
        try {
            req.setUpdatedDate(LocalDateTime.now());
            contentRequestRepository.save(req);
        } catch (Exception ex) {
            log.warn("Content request ara kayıt başarısız: id={}, hata={}", req.getContentRequestId(), ex.getMessage());
        }
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    private String toJsonArray(List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception ex) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}
