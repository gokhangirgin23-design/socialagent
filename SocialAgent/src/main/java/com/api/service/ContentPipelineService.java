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
import com.api.ai.OpenAiImageService;
import com.api.ai.VeoVideoService;
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
    private final OpenAiImageService openAiImageService;
    private final GeminiImageService geminiImageService;
    private final VeoVideoService veoVideoService;
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

            // Kullanıcının güncel sektör/alt sektörünü DB'den çek (görsel üretimde sert kısıt)
            String sectorContext = loadUserSectorContext(req.getUserId());
            log.info("Sektör bağlamı yüklendi: contentRequestId={}, sektör={}", contentRequestId, sectorContext);

            // Brand DNA: önbellekte varsa kullan, yoksa üret
            String brandDna = req.getBrandDnaJson();
            if (brandDna == null || brandDna.isBlank()) {
                brandDna = generateBrandDna(req, reportContent, sectorContext);
                if (brandDna != null) {
                    req.setBrandDnaJson(brandDna);
                    saveQuiet(req);
                }
            }

            // Ürün görseli: private S3 URL → pre-signed URL (1 saat geçerli)
            // Hem OpenAI (kendi HTTP fetch'i) hem Gemini Vision (Google sunucusundan fetch) kabul eder.
            // Byte indirip base64'e çevirmeye gerek yok.
            String productImageData = s3UploadService.presign(req.getProductImageUrl());
            if (req.getProductImageUrl() != null && productImageData == null) {
                log.warn("Ürün görseli pre-sign başarısız; referanssız devam ediliyor: contentRequestId={}", contentRequestId);
            }

            // Gemini Vision ile ürün tipi + ideal arka plan analizi
            String productContext = null;
            if (productImageData != null) {
                productContext = aiAnalysisService.analyzeProductImage(productImageData);
                log.info("Ürün görseli analizi: contentRequestId={}, ürünContext={}",
                        contentRequestId, productContext != null ? "OK" : "atlandı");
            }

            // Görsel üretim + S3 yükleme (productImageData ile — S3 URL değil)
            VisualResult visual = generateAndUploadVisuals(req, brandDna, reportContent, sectorContext, productContext, productImageData);

            // Ürün görseli kullanıldı; DB'den temizle
            if (req.getProductImageUrl() != null) {
                req.setProductImageUrl(null);
                saveQuiet(req);
            }

            // Görsel/video üretim servisi aktifken üretim başarısızsa FAILED — bakiye düşülmez
            boolean imageServiceActive = req.getContentType() == ContentType.REEL
                    ? veoVideoService.isActive()
                    : openAiImageService.isActive() || geminiImageService.isActive();
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

    private String generateBrandDna(ContentRequest req, String reportContent, String sectorContext) {
        // Kullanıcının kendi hesabına ait son 10 post caption'ını al
        String postsContext = loadOwnPostsCaptions(req.getReportId());
        // Görsel analiz verilerini al (ürün kategorisi, atmosfer, renkler, çekim stili)
        String visualPatterns = loadVisualPatterns(req.getReportId());
        String prompt = ContentPrompts.forBrandDna(postsContext, reportContent, visualPatterns, sectorContext);
        String dna = aiAnalysisService.generateBrandDna(prompt);
        if (dna != null) {
            log.info("Brand DNA üretildi: contentRequestId={}", req.getContentRequestId());
        } else {
            log.info("Brand DNA üretilemedi (AI kapalı veya hata); atlanıyor: contentRequestId={}",
                    req.getContentRequestId());
        }
        return dna;
    }

    /**
     * Kullanıcının DB'deki güncel sektör ve alt sektör adını yükler.
     * Görsel üretimde ürün kategorisini garantilemek için sert kısıt olarak kullanılır.
     * user_info ⋈ sector ⋈ subsector — eski stil "=" (CLAUDE.md Madde 6).
     */
    private String loadUserSectorContext(UUID userId) {
        // Alt sektörü de olan kullanıcı için tam bağlam
        String sqlFull = """
                SELECT s.name AS sector_name, ss.name AS subsector_name
                FROM user_info ui, sector s, subsector ss
                WHERE ui.sector_id = s.sector_id
                  AND ui.subsector_id = ss.subsector_id
                  AND ui.user_id = ?
                """;
        try {
            List<String[]> rows = jdbcTemplate.query(sqlFull, (rs, rowNum) ->
                    new String[]{rs.getString("sector_name"), rs.getString("subsector_name")}, userId);
            if (!rows.isEmpty()) {
                return "Sektör: " + rows.get(0)[0] + ", Alt Sektör: " + rows.get(0)[1];
            }
        } catch (Exception ex) {
            log.warn("Sektör bağlamı (full) yüklenemedi: hata={}", ex.getMessage());
        }

        // Alt sektörü yoksa yalnız sektör
        String sqlSector = """
                SELECT s.name AS sector_name
                FROM user_info ui, sector s
                WHERE ui.sector_id = s.sector_id
                  AND ui.user_id = ?
                """;
        try {
            List<String> rows = jdbcTemplate.queryForList(sqlSector, String.class, userId);
            if (!rows.isEmpty()) {
                return "Sektör: " + rows.get(0);
            }
        } catch (Exception ex) {
            log.warn("Sektör bağlamı yüklenemedi: hata={}", ex.getMessage());
        }
        return null;
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

    /**
     * Görsel analizden ürün kategorisi, atmosfer, renk ve çekim stili özetini çeker.
     * Brand DNA'nın mainProductOrService alanını beslemek için kullanılır.
     * Hem OWN hem SECTOR + MONITORED post_analysis kayıtlarından çeker.
     */
    private String loadVisualPatterns(UUID reportId) {
        // analysis_json içinden visual alt alanlarını çek (OWN + SECTOR + MONITORED)
        String sql = """
                SELECT pa.analysis_json
                FROM post_analysis pa, social_post sp, report r
                WHERE pa.social_post_id = sp.social_post_id
                  AND sp.request_id = r.request_id
                  AND r.report_id = ?
                  AND pa.analysis_json IS NOT NULL
                ORDER BY sp.post_date DESC
                LIMIT 15
                """;
        try {
            List<String> analysisJsonList = jdbcTemplate.queryForList(sql, String.class, reportId);
            if (analysisJsonList.isEmpty()) return null;

            // Her analiz JSON'undan visual alanlarını basit string eşleşmesiyle çek
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String json : analysisJsonList) {
                if (json == null || json.isBlank()) continue;
                // visual alt objesini bul
                int visualIdx = json.indexOf("\"visual\":");
                if (visualIdx < 0) continue;
                String visualSection = json.substring(visualIdx);

                String productCategory = extractSimpleField(visualSection, "productCategory");
                String specificProduct = extractSimpleField(visualSection, "specificProduct");
                String shootingStyle = extractSimpleField(visualSection, "shootingStyle");
                String lightingStyle = extractSimpleField(visualSection, "lightingStyle");
                String backgroundType = extractSimpleField(visualSection, "backgroundType");
                String atmosphere = extractSimpleField(visualSection, "atmosphere");

                // En az bir değer varsa ekle
                if (productCategory != null || specificProduct != null || atmosphere != null) {
                    count++;
                    sb.append("Görsel ").append(count).append(": ");
                    if (specificProduct != null) sb.append("Ürün=").append(specificProduct).append(", ");
                    if (productCategory != null) sb.append("Kategori=").append(productCategory).append(", ");
                    if (atmosphere != null) sb.append("Atmosfer=").append(atmosphere).append(", ");
                    if (shootingStyle != null) sb.append("Çekim=").append(shootingStyle).append(", ");
                    if (lightingStyle != null) sb.append("Işık=").append(lightingStyle).append(", ");
                    if (backgroundType != null) sb.append("Arka plan=").append(backgroundType);
                    sb.append("\n");
                }
                if (count >= 10) break;
            }
            return sb.isEmpty() ? null : sb.toString();
        } catch (Exception ex) {
            log.warn("Görsel analiz verileri yüklenemedi: hata={}", ex.getMessage());
            return null;
        }
    }

    // JSON string değeri basitçe çeker (parse bağımlılığı yok)
    private String extractSimpleField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int valueStart = idx + key.length();
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        if (valueStart >= json.length()) return null;
        char first = json.charAt(valueStart);
        if (first == '"') {
            int end = json.indexOf('"', valueStart + 1);
            if (end > valueStart) {
                String val = json.substring(valueStart + 1, end);
                return (val.isBlank() || val.equals("null")) ? null : val;
            }
        }
        return null;
    }

    // ============================================================
    // Görsel üretim + S3
    // ============================================================

    private record VisualResult(List<String> urls, int expected) {
        boolean anyFailed() { return urls.size() < expected; }
        int failCount()     { return expected - urls.size(); }
    }

    private VisualResult generateAndUploadVisuals(ContentRequest req, String brandDna,
            String reportContent, String sectorContext, String productContext, String productImageData) {
        ContentType type = req.getContentType();
        List<String> urls = new ArrayList<>();
        String editInstruction = req.getEditInstruction();

        if (type == ContentType.CAROUSEL) {
            String[] roles = {"HOOK", "CONTENT", "CTA"};
            for (int i = 0; i < roles.length; i++) {
                String prompt = ContentPrompts.forVisual(brandDna, reportContent, "CAROUSEL", i, roles[i],
                        req.isIncludeTextInVisual(), editInstruction, sectorContext, productContext);
                String url = generateAndUpload(req, prompt, i, productImageData);
                if (url != null) urls.add(url);
            }
            return new VisualResult(urls, roles.length);
        } else if (type == ContentType.REEL) {
            String prompt = ContentPrompts.forVideo(brandDna, reportContent, editInstruction, sectorContext, productContext);
            String url = generateAndUploadVideo(req, prompt, editInstruction, productImageData);
            if (url != null) urls.add(url);
            return new VisualResult(urls, 1);
        } else {
            String prompt = ContentPrompts.forVisual(brandDna, reportContent, type.name(), 0, null,
                    req.isIncludeTextInVisual(), editInstruction, sectorContext, productContext);
            String url = generateAndUpload(req, prompt, 0, productImageData);
            if (url != null) urls.add(url);
            return new VisualResult(urls, 1);
        }
    }

    private String generateAndUploadVideo(ContentRequest req, String prompt, String editInstruction, String productImageData) {
        byte[] videoBytes = veoVideoService.generateVideo(prompt, productImageData);
        if (videoBytes == null) {
            if (!veoVideoService.isActive()) {
                log.info("Veo pasif; REEL için Gemini görsel fallback: contentRequestId={}", req.getContentRequestId());
                String imagePrompt = ContentPrompts.forVisual(null, null, "REEL", 0, null, false, editInstruction, null, null);
                return generateAndUpload(req, imagePrompt, 0, null);
            }
            log.warn("Veo video üretilemedi: contentRequestId={}", req.getContentRequestId());
            return null;
        }
        return s3UploadService.uploadVideo(videoBytes, req.getUserId(), req.getContentRequestId());
    }

    private String generateAndUpload(ContentRequest req, String prompt, int index, String productImageData) {
        String size = sizeForType(req.getContentType());
        // productImageData: S3'ten SDK ile indirilen base64 data URL (HTTP ile çekilemez; private bucket)
        byte[] imageBytes = openAiImageService.generateImage(prompt, productImageData, size);

        if (imageBytes == null && geminiImageService.isActive()) {
            log.warn("OpenAI görsel başarısız; Gemini fallback deneniyor: contentRequestId={}, index={}",
                    req.getContentRequestId(), index);
            imageBytes = geminiImageService.generateImage(prompt, productImageData);
        }

        if (imageBytes == null) {
            log.warn("Görsel üretilemedi: contentRequestId={}, index={}", req.getContentRequestId(), index);
            return null;
        }
        return s3UploadService.upload(imageBytes, req.getUserId(), req.getContentRequestId(), index);
    }

    private static String sizeForType(ContentType type) {
        return switch (type) {
            case STORY -> "1024x1536";
            default    -> "1024x1024";
        };
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

        // Düzenleme denemesi başarısız olursa hak yenmez — sayacı geri al
        if (status == ContentRequestStatus.FAILED
                && req.getEditInstruction() != null
                && !req.getEditInstruction().isBlank()
                && req.getEditCount() > 0) {
            req.setEditCount(req.getEditCount() - 1);
            log.info("Düzenleme başarısız; editCount geri alındı: id={}, yeni editCount={}",
                    req.getContentRequestId(), req.getEditCount());
        }

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
