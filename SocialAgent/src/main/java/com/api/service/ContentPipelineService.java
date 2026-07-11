package com.api.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.api.ai.AiAnalysisService;
import com.api.ai.ContentPrompts;
import com.api.ai.GeminiImageService;
import com.api.ai.OpenAiImageService;
import com.api.ai.VeoVideoService;
import com.api.config.AppProperties;
import com.api.config.CreditCatalog;
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

            // Brand DNA: kendi önbelleğinde varsa kullan; yoksa aynı rapora ait başka bir
            // content_request'in DNA'sını ara (aynı raporun DNA'sı değişmez — yeniden üretim
            // gereksiz maliyet); o da yoksa üret.
            String brandDna = req.getBrandDnaJson();
            if (brandDna == null || brandDna.isBlank()) {
                brandDna = loadCachedBrandDna(req.getReportId());
                if (brandDna != null) {
                    log.info("Rapor bazlı Brand DNA cache'ten kullanıldı (AI çağrısı atlandı): reportId={}", req.getReportId());
                    req.setBrandDnaJson(brandDna);
                    saveQuiet(req);
                } else {
                    brandDna = generateBrandDna(req, reportContent, sectorContext);
                    if (brandDna != null) {
                        req.setBrandDnaJson(brandDna);
                        saveQuiet(req);
                    }
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

            // Düzenleme ise: bir önceki üretilen görsel(ler) referans alınır (küçük bir talimatla
            // tüm görselin yeniden üretilmesini engellemek için — visual_urls DB'de henüz
            // ÜZERİNE YAZILMADAN önce okunmalı, aksi halde referans kaybolur).
            List<String> previousVisualUrls = (req.getEditInstruction() != null && !req.getEditInstruction().isBlank())
                    ? parseVisualUrlsRaw(req.getVisualUrls())
                    : List.of();

            // Görsel üretim + S3 yükleme (productImageData ile — S3 URL değil)
            VisualResult visual = generateAndUploadVisuals(req, brandDna, reportContent, sectorContext, productContext, productImageData, previousVisualUrls);

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

            // Ödeme: yalnızca COMPLETED olunca krediyi düş. İçerik zaten teslim edilmiş olduğundan
            // (görsel S3'e yüklendi, DB'ye yazıldı) bu farklı bir transaction'dır — gerçek atomiklik
            // yoktur. Bu yüzden hata asla sessizce yutulmaz: sonuç content_request'e kalıcı olarak
            // yazılır (credit_debited/credit_debit_error) ve ERROR seviyesinde loglanır; admin'in
            // POST /admin/retry-failed-content-debits ile tetiklediği retryFailedDebits() bunu bulup
            // tekrar dener.
            // ÖNEMLİ: edit() aynı content_request_id'yi PENDING'e alıp process()'i tekrar tetikler
            // (bkz. ContentRequestService.edit) — creditDebited zaten 1 ise (ilk üretimde başarıyla
            // düşüldüyse) burada TEKRAR düşülmez, aksi halde her düzenleme kullanıcıdan ikinci kez
            // ücret alırdı. creditDebited bu metodun başında findById ile yüklenen değeri taşır,
            // debitOnCompleted() bu çağrıdan önce hiç çalışmadığından güncel/doğru haldedir.
            if (shouldDebitOnCompletion(req)) {
                debitOnCompleted(req);
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
     * Aynı rapora ait, daha önce üretilmiş Brand DNA'yı arar (rapor bazlı yeniden kullanım — maliyet).
     * Aynı rapordan açılan farklı içerik istekleri (POST/STORY/CAROUSEL vb.) DNA'yı paylaşabilir;
     * bulunursa AI çağrısı hiç yapılmaz.
     */
    private String loadCachedBrandDna(UUID reportId) {
        String sql = """
                SELECT cr.brand_dna_json
                FROM content_request cr
                WHERE cr.report_id = ?
                  AND cr.brand_dna_json IS NOT NULL
                  AND cr.active = 1
                ORDER BY cr.created_date DESC
                LIMIT 1
                """;
        try {
            List<String> rows = jdbcTemplate.queryForList(sql, String.class, reportId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception ex) {
            log.warn("Rapor bazlı Brand DNA cache sorgusu başarısız: reportId={}, hata={}", reportId, ex.getMessage());
            return null;
        }
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

    // Görsel analiz sorgu satırı: analysis_json + kaynak (OWN|MONITORED|SECTOR) + sektör hesap adı
    private record VisualPatternRow(String analysisJson, String sourceType, String sectorAccountName) {
    }

    /**
     * SECTOR hesapları arasında, gerçek konusu (productCategory) diğer hiçbir sektör hesabıyla
     * örtüşmeyen (Apify'ın keyword aramasıyla yanlışlıkla eşleştirdiği alakasız bir hesap
     * olması muhtemel) hesap adlarını döner — bkz. SectorRelevanceFilter.
     */
    private Set<String> findIrrelevantSectorAccounts(UUID reportId) {
        String sql = """
                SELECT sp.sector_account_name, pa.analysis_json
                FROM post_analysis pa, social_post sp, report r
                WHERE pa.social_post_id = sp.social_post_id
                  AND sp.request_id = r.request_id
                  AND r.report_id = ?
                  AND sp.source_type = 'SECTOR'
                  AND sp.sector_account_name IS NOT NULL
                  AND pa.analysis_json IS NOT NULL
                """;
        try {
            List<String[]> rows = jdbcTemplate.query(sql, (rs, rowNum) ->
                    new String[]{rs.getString("sector_account_name"), rs.getString("analysis_json")}, reportId);
            Map<String, List<String>> categoriesByAccount = new LinkedHashMap<>();
            for (String[] row : rows) {
                String category = SectorRelevanceFilter.extractProductCategory(row[1]);
                if (category != null) {
                    categoriesByAccount.computeIfAbsent(row[0], k -> new ArrayList<>()).add(category);
                }
            }
            Set<String> irrelevant = SectorRelevanceFilter.findIrrelevantAccounts(categoriesByAccount);
            if (!irrelevant.isEmpty()) {
                log.warn("Sektör aramasında alakasız hesap(lar) tespit edildi, Brand DNA'dan dışlanıyor: reportId={}, hesaplar={}",
                        reportId, irrelevant);
            }
            return irrelevant;
        } catch (Exception ex) {
            log.warn("Sektör hesap alaka analizi başarısız (atlanmadan devam edilir): reportId={}, hata={}",
                    reportId, ex.getMessage());
            return Set.of();
        }
    }

    /**
     * Görsel analizden ürün kategorisi, atmosfer, renk ve çekim stili özetini çeker.
     * Brand DNA'nın mainProductOrService alanını beslemek için kullanılır.
     * Hem OWN hem SECTOR + MONITORED post_analysis kayıtlarından çeker; her satır
     * KENDİ/RAKİP/SEKTÖR etiketiyle işaretlenir (Brand DNA yalnızca KENDİ'den kimlik alsın diye).
     */
    private String loadVisualPatterns(UUID reportId) {
        // Apify'ın sektör aramasıyla bulduğu, gerçek konusu diğer sektör hesaplarıyla hiç
        // örtüşmeyen (alakasız) hesapları önceden tespit et — bkz. SectorRelevanceFilter.
        Set<String> irrelevantSectorAccounts = findIrrelevantSectorAccounts(reportId);

        // analysis_json + source_type çek (OWN + SECTOR + MONITORED).
        // KENDİ postları her zaman önce sıralanır (source_type = 'OWN' DESC), sonra tarih DESC —
        // aksi halde çok sayıda rakip/sektör postu olan bir raporda, KENDİ'nin az sayıdaki postu
        // sırf daha eski tarihli olduğu için LIMIT 15'in dışında kalabilirdi. KENDİ, DNA'nın ana
        // kimlik kaynağı olduğundan (bkz. sınıf yorumu) asla rakip verisiyle dışarı itilmemeli.
        String sql = """
                SELECT pa.analysis_json, sp.source_type, sp.sector_account_name
                FROM post_analysis pa, social_post sp, report r
                WHERE pa.social_post_id = sp.social_post_id
                  AND sp.request_id = r.request_id
                  AND r.report_id = ?
                  AND pa.analysis_json IS NOT NULL
                ORDER BY (sp.source_type = 'OWN') DESC, sp.post_date DESC
                LIMIT 15
                """;
        try {
            List<VisualPatternRow> rows = jdbcTemplate.query(sql,
                    (rs, rowNum) -> new VisualPatternRow(rs.getString("analysis_json"), rs.getString("source_type"),
                            rs.getString("sector_account_name")),
                    reportId);
            if (rows.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (VisualPatternRow row : rows) {
                if (row.analysisJson() == null || row.analysisJson().isBlank()) continue;
                if ("SECTOR".equals(row.sourceType()) && irrelevantSectorAccounts.contains(row.sectorAccountName())) {
                    continue; // Apify'ın yanlış eşleştirdiği alakasız sektör hesabı — DNA'ya karışmasın
                }
                try {
                    JsonNode visual = objectMapper.readTree(row.analysisJson()).path("visual");
                    if (visual.isMissingNode() || visual.isNull()) continue;

                    String specificProduct = textField(visual, "specificProduct");
                    String productCategory = textField(visual, "productCategory");
                    String atmosphere = textField(visual, "atmosphere");
                    String shootingStyle = textField(visual, "shootingStyle");
                    String lightingStyle = textField(visual, "lightingStyle");
                    String backgroundType = textField(visual, "backgroundType");
                    String composition = textField(visual, "composition");
                    String colorPalette = arrayField(visual, "colorPalette");
                    String propsAndDecor = arrayField(visual, "propsAndDecor");
                    // sceneDescription/visualThemes daha önce hiç çıkarılmıyordu — backgroundType gibi
                    // tek kelimelik alanların kaçırdığı ayırt edici detaylar (ör. "Boğaz manzaralı teras")
                    // genelde bu iki alanda yer alır; DNA'nın "typicalBackground"ı çoğunluk deseninin
                    // (ör. "restoran iç mekan") gölgesinde bırakabileceği azınlık ama marka-tanımlayıcı
                    // sinyalleri kaybetmemek için eklendi.
                    String visualThemes = arrayField(visual, "visualThemes");
                    String sceneDescription = textField(visual, "sceneDescription");

                    // En az bir değer varsa ekle
                    if (productCategory == null && specificProduct == null && atmosphere == null) continue;

                    count++;
                    sb.append("Görsel ").append(count).append(" ").append(sourceLabel(row.sourceType())).append(": ");
                    if (specificProduct != null) sb.append("Ürün=").append(specificProduct).append(", ");
                    if (productCategory != null) sb.append("Kategori=").append(productCategory).append(", ");
                    if (atmosphere != null) sb.append("Atmosfer=").append(atmosphere).append(", ");
                    if (shootingStyle != null) sb.append("Çekim=").append(shootingStyle).append(", ");
                    if (lightingStyle != null) sb.append("Işık=").append(lightingStyle).append(", ");
                    if (backgroundType != null) sb.append("Arka plan=").append(backgroundType).append(", ");
                    if (colorPalette != null) sb.append("Renkler=").append(colorPalette).append(", ");
                    if (propsAndDecor != null) sb.append("Dekor=").append(propsAndDecor).append(", ");
                    if (visualThemes != null) sb.append("Temalar=").append(visualThemes).append(", ");
                    if (composition != null) sb.append("Kompozisyon=").append(composition).append(", ");
                    if (sceneDescription != null) sb.append("Sahne=").append(sceneDescription);
                    sb.append("\n");
                } catch (Exception ex) {
                    log.warn("Görsel analiz satırı ayrıştırılamadı; atlanıyor: hata={}", ex.getMessage());
                    continue;
                }
                if (count >= 10) break;
            }
            return sb.isEmpty() ? null : sb.toString();
        } catch (Exception ex) {
            log.warn("Görsel analiz verileri yüklenemedi: hata={}", ex.getMessage());
            return null;
        }
    }

    // social_post.source_type -> Brand DNA satırı etiketi (Madde 7)
    private String sourceLabel(String sourceType) {
        if (sourceType == null) return "";
        return switch (sourceType) {
            case "OWN" -> "[KENDİ]";
            case "MONITORED" -> "[RAKİP]";
            case "SECTOR" -> "[SEKTÖR]";
            default -> "";
        };
    }

    // JSON string alanı null-safe çeker
    private String textField(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText(null);
        return (text == null || text.isBlank() || text.equalsIgnoreCase("null")) ? null : text;
    }

    // JSON string dizisini virgülle birleştirir (colorPalette, propsAndDecor gibi array alanlar için)
    private String arrayField(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isArray() || value.isEmpty()) return null;
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            String text = item.asText(null);
            if (text != null && !text.isBlank()) items.add(text);
        }
        return items.isEmpty() ? null : String.join(", ", items);
    }

    // ============================================================
    // Görsel üretim + S3
    // ============================================================

    private record VisualResult(List<String> urls, int expected) {
        boolean anyFailed() { return urls.size() < expected; }
        int failCount()     { return expected - urls.size(); }
    }

    private VisualResult generateAndUploadVisuals(ContentRequest req, String brandDna,
            String reportContent, String sectorContext, String productContext, String productImageData,
            List<String> previousVisualUrls) {
        ContentType type = req.getContentType();
        List<String> urls = new ArrayList<>();
        String editInstruction = req.getEditInstruction();

        if (type == ContentType.CAROUSEL) {
            String[] roles = {"HOOK", "CONTENT", "CTA"};
            for (int i = 0; i < roles.length; i++) {
                String prompt = ContentPrompts.forVisual(brandDna, reportContent, "CAROUSEL", i, roles[i],
                        req.isIncludeTextInVisual(), editInstruction, sectorContext, productContext);
                String referenceImage = editReferenceFor(previousVisualUrls, i, productImageData);
                String url = generateAndUpload(req, prompt, i, referenceImage);
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
            String referenceImage = editReferenceFor(previousVisualUrls, 0, productImageData);
            String url = generateAndUpload(req, prompt, 0, referenceImage);
            if (url != null) urls.add(url);
            return new VisualResult(urls, 1);
        }
    }

    /**
     * Bir görsel için kullanılacak referans görseli belirler.
     * Düzenlemede (bir önceki görsel varsa) ONA sadık kalınır — küçük bir talimat
     * tüm görseli yeniden üretmesin diye bir önceki üretim /images/edits referansı olur.
     * Düzenleme değilse (ilk üretim) kullanıcının yüklediği ürün görseli referans olur.
     */
    private String editReferenceFor(List<String> previousVisualUrls, int index, String productImageData) {
        if (previousVisualUrls != null && index < previousVisualUrls.size()) {
            String previousUrl = previousVisualUrls.get(index);
            if (previousUrl != null && !previousUrl.isBlank()) {
                String presigned = s3UploadService.presign(previousUrl);
                if (presigned != null) {
                    return presigned;
                }
                log.warn("Önceki görsel presign edilemedi; ürün görseline/dolu üretime düşülüyor: index={}", index);
            }
        }
        return productImageData;
    }

    /** visual_urls JSON kolonunu ham S3 URL listesine çevirir (presign YAPMAZ — çağıran gerektiğinde presign eder). */
    private List<String> parseVisualUrlsRaw(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception ex) {
            log.warn("visual_urls ayrıştırılamadı: hata={}", ex.getMessage());
            return List.of();
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
        // Görsel içi yazı istenmişse text rendering netliği için high; aksi hâlde config'teki ekonomik tier (maliyet)
        String quality = req.isIncludeTextInVisual() ? "high" : appProperties.getContent().getImageQuality();
        // productImageData: S3'ten SDK ile indirilen base64 data URL (HTTP ile çekilemez; private bucket)
        byte[] imageBytes = openAiImageService.generateImage(prompt, productImageData, size, quality);

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
        // bkz. ContentPrompts.forVisual() yorumu — raporun aksiyon önerileri genelde özet/tablodan
        // sonra gelir, düşük bir karakter sınırı bunlara hiç ulaşmadan raporu keser.
        String reportSnippet = reportContent.length() > 3000
                ? reportContent.substring(0, 3000) + "..."
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
    // Ödeme (kredi düşümü) — reconciliation
    // ============================================================

    // Reconciliation poison guard: aynı istek için en fazla bu kadar düşüm denemesi yapılır
    private static final int MAX_DEBIT_ATTEMPTS = 5;

    /**
     * process() COMPLETED sonrası kredi düşümü denemesi yapmalı mı? Ödeme kapalıysa VEYA
     * bu content_request için kredi zaten başarıyla düşüldüyse (creditDebited=1 — ilk üretimde
     * veya edit() ile tetiklenen bir önceki yeniden-üretimde) hayır: edit() aynı content_request'i
     * PENDING'e alıp process()'i tekrar çalıştırdığından, bu kontrol olmazsa her düzenleme
     * kullanıcıdan kredi maliyetini ikinci (üçüncü, ...) kez tahsil ederdi.
     */
    private boolean shouldDebitOnCompletion(ContentRequest req) {
        return appProperties.getPayment().isEnabled() && req.getCreditDebited() != 1;
    }

    /**
     * İçerik COMPLETED olduğunda bakiyeyi düşer. Hata durumunda sonucu kalıcı olarak
     * content_request'e yazar (bkz. sınıf yorumu, process() içindeki çağrı noktası).
     *
     * @return true ise kredi bu çağrıda başarıyla düşüldü
     */
    private boolean debitOnCompleted(ContentRequest req) {
        UUID contentRequestId = req.getContentRequestId();
        try {
            int creditCost = CreditCatalog.creditCostFor(req.getContentType());
            boolean debited = paymentService.tryDebitCredits(req.getUserId(), creditCost,
                    req.getContentType().name(), contentRequestId);
            if (debited) {
                markCreditDebitState(contentRequestId, true, null);
                log.info("COMPLETED — içerik kredisi düşüldü: contentRequestId={}, creditCost={}",
                        contentRequestId, creditCost);
                return true;
            }
            // Kredi yetersiz — kullanıcı sonradan kredi yüklerse retryFailedDebits() tekrar dener.
            markCreditDebitState(contentRequestId, false, "INSUFFICIENT_CREDITS");
            log.warn("COMPLETED — içerik kredi düşümü başarısız (yetersiz kredi): contentRequestId={}, userId={}",
                    contentRequestId, req.getUserId());
            return false;
        } catch (Exception ex) {
            // SQL/altyapı hatası: içerik ZATEN teslim edilmiş durumda. Hata yutulmaz.
            markCreditDebitState(contentRequestId, false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.error("COMPLETED — içerik kredi düşümü sırasında hata (içerik teslim edilmiş, kredi düşmedi; "
                    + "reconciliation gerekiyor): contentRequestId={}, userId={}, hata={}",
                    contentRequestId, req.getUserId(), ex.getMessage(), ex);
            return false;
        }
    }

    /** Kredi düşüm sonucunu content_request'e kalıcı olarak yazar (bağımsız auto-commit). */
    private void markCreditDebitState(UUID contentRequestId, boolean debited, String error) {
        jdbcTemplate.update("""
                UPDATE content_request
                SET credit_debited = ?, credit_debit_error = ?,
                    credit_debit_attempts = credit_debit_attempts + 1, updated_date = ?
                WHERE content_request_id = ?
                """, debited ? 1 : 0, error, LocalDateTime.now(), contentRequestId);
    }

    /**
     * Reconciliation: COMPLETED olup kredisi hâlâ düşmemiş içerik isteklerini bulur ve düşümü
     * tekrar dener. Admin tarafından POST /admin/retry-failed-content-debits ile tetiklenir.
     *
     * @return bu çağrıda başarıyla düşümü tamamlanan kayıt sayısı
     */
    public int retryFailedDebits() {
        String sql = """
                SELECT content_request_id
                FROM content_request
                WHERE active = 1 AND status = 'COMPLETED' AND credit_debited = 0
                  AND credit_debit_attempts < ?
                """;
        List<UUID> pendingIds = jdbcTemplate.queryForList(sql, UUID.class, MAX_DEBIT_ATTEMPTS);

        if (pendingIds.isEmpty()) {
            log.info("Tekrar denenecek düşmemiş içerik kredi kaydı bulunamadı.");
            return 0;
        }

        int recovered = 0;
        for (UUID id : pendingIds) {
            ContentRequest req = contentRequestRepository.findById(id).orElse(null);
            if (req == null) continue;
            if (debitOnCompleted(req)) {
                recovered++;
            }
        }
        log.info("İçerik kredi düşümü reconciliation tamamlandı: toplam={}, kurtarılan={}",
                pendingIds.size(), recovered);
        return recovered;
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
