package com.api.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.api.ai.AiAnalysisService;
import com.api.ai.ContentPrompts;
import com.api.ai.GeminiImageService;
import com.api.ai.OpenAiImageService;
import com.api.ai.VeoVideoService;
import com.api.apify.ApifyClient;
import com.api.apify.ApifyPost;
import com.api.config.AppProperties;
import com.api.config.CreditCatalog;
import com.api.dto.repository.ContentRequestRepository;
import com.api.dto.repository.UserAccountDnaRepository;
import com.api.entity.ContentRequest;
import com.api.entity.ContentRequestStatus;
import com.api.entity.ContentType;
import com.api.entity.SocialPost;
import com.api.entity.UserAccountDna;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * İçerik üretim pipeline'ı (görsel + caption + S3).
 * ContentWorker bu servisi çağırır; @Transactional DEĞİL (dış API çağrıları uzun sürebilir).
 * Status güncellemeleri bağımsız auto-commit'tir (ScrapePipelineService ile aynı felsefe).
 *
 * İçerik üretimi RAPORDAN TAMAMEN BAĞIMSIZDIR (bkz. ICERIK-RAPOR-AYRISTIRMA-SPEC.md) — rapor
 * içeriği hiçbir prompt'a enjekte edilmez. Brand DNA rapora değil kullanıcının bağlı sosyal
 * hesabına bağlıdır (user_account_dna, bkz. resolveAccountDna).
 *
 * Akış:
 *   1) content_request yükle → PROCESSING
 *   2) socialAccountId doluysa Brand DNA üret / önbellekten al (OpenAI) — boşsa DNA'sız devam
 *   3) Görsel üret (Gemini Image) → S3'e yükle
 *   4) Caption + hashtag + CTA üret (OpenAI)
 *   5) Kaydet → COMPLETED veya FAILED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentPipelineService {

    private final ContentRequestRepository contentRequestRepository;
    private final UserAccountDnaRepository userAccountDnaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AiAnalysisService aiAnalysisService;
    private final OpenAiImageService openAiImageService;
    private final GeminiImageService geminiImageService;
    private final VeoVideoService veoVideoService;
    private final S3UploadService s3UploadService;
    private final PaymentService paymentService;
    private final AppProperties appProperties;
    private final ApifyClient apifyClient;

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
            // Kullanıcının güncel sektör/alt sektörünü DB'den çek (görsel üretimde sert kısıt)
            String sectorContext = loadUserSectorContext(req.getUserId());
            log.info("Sektör bağlamı yüklendi: contentRequestId={}, sektör={}", contentRequestId, sectorContext);

            // Brand DNA: içerik üretimi rapordan tamamen bağımsızdır (bkz. sınıf yorumu).
            // socialAccountId doluysa hesap bazlı DNA cache'i her process() çağrısında (ilk üretim
            // VEYA edit akışında yeniden işleme) resolveAccountDna üzerinden KONTROL EDİLİR — DNA
            // değişmediyse tek bir ucuz cache SELECT'i dışında maliyeti yoktur (bkz.
            // loadCachedAccountDna), ama AccountDnaCacheService.invalidateAccountDnaCache hesap adı/
            // sektör/alt sektör değişiminde cache'i pasife aldıysa burada otomatik yeniden üretilir.
            // ÖNEMLİ: eskiden brandDnaJson content_request'e ilk üretimde donduruluyor ve edit()
            // akışında hiç sorgulanmadan doğrudan tekrar kullanılıyordu — bu, hesap/sektör değişimi
            // SONRASI yapılan bir "Düzenle" isteğinin invalidation'dan hiç etkilenmemesine (eski
            // caption/görsel kimliğinin sonsuza dek kalıcı olmasına) yol açıyordu. Artık her
            // process() çağrısı güncel DNA'yı sorar; yalnızca bu sefer üretilemezse (ör. AI geçici
            // hata veya hiç post bulunamaması) content_request'teki önceki DNA'ya düşülür.
            String brandDna = null;
            if (req.getSocialAccountId() != null) {
                brandDna = resolveAccountDna(req.getUserId(), req.getSocialAccountId(), sectorContext);
                if (brandDna == null) {
                    brandDna = req.getBrandDnaJson();
                } else if (!brandDna.equals(req.getBrandDnaJson())) {
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

            // Düzenleme ise: bir önceki üretilen görsel(ler) referans alınır (küçük bir talimatla
            // tüm görselin yeniden üretilmesini engellemek için — visual_urls DB'de henüz
            // ÜZERİNE YAZILMADAN önce okunmalı, aksi halde referans kaybolur).
            List<String> previousVisualUrls = (req.getEditInstruction() != null && !req.getEditInstruction().isBlank())
                    ? parseVisualUrlsRaw(req.getVisualUrls())
                    : List.of();

            // Görsel üretim + S3 yükleme (productImageData ile — S3 URL değil)
            VisualResult visual = generateAndUploadVisuals(req, brandDna, sectorContext, productContext, productImageData, previousVisualUrls);

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
            applyContentMetadata(req, brandDna);

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

    // Spec (ICERIK-RAPOR-AYRISTIRMA-SPEC.md §2.3): hesap DNA'sı yalnızca son 5 gönderiden çıkarılır
    private static final int ACCOUNT_DNA_POST_LIMIT = 5;

    // Hesap post satırı: caption + varsa (DB'den veya taze hesaplanmış) görsel/metrik analiz JSON'u
    private record AccountPostRow(String caption, String analysisJson) {
    }

    // user_social_account'tan okunan, Apify çağrısı için gereken minimum hesap bilgisi
    private record OwnAccountInfo(String platform, String accountName, String profileUrl) {
    }

    /**
     * Hesap bazlı Brand DNA cache akışı (spec §2.3).
     * 1) user_account_dna'da (userId, socialAccountId) kaydı varsa direkt döner — başka hiçbir
     *    şey çalıştırılmaz (ne DB post sorgusu ne Apify ne AI çağrısı).
     * 2) Yoksa: DB'de zaten scrape edilmiş son 5 OWN gönderi kullanılır; hiç yoksa Apify'dan taze
     *    çekilir (bu taze gönderiler social_post'a YAZILMAZ — yalnızca DNA üretimi için bellekte
     *    kullanılır, bkz. fetchFreshAccountPosts).
     * 3) Hiç gönderi bulunamazsa DNA'sız devam edilir, cache kaydı açılmaz.
     * 4) Bulunan gönderilerden DNA üretilip user_account_dna'ya kalıcı olarak cache'lenir.
     */
    private String resolveAccountDna(UUID userId, UUID socialAccountId, String sectorContext) {
        String cached = loadCachedAccountDna(userId, socialAccountId);
        if (cached != null) {
            log.info("Hesap DNA cache'ten kullanıldı (AI çağrısı atlandı): userId={}, socialAccountId={}",
                    userId, socialAccountId);
            return cached;
        }

        // Güncel hesap adı: eski hesap adına ait postların yeni DNA üretimine sızmaması için
        // loadExistingAccountPosts'a filtre olarak geçilir (bkz. o metodun yorumu).
        OwnAccountInfo account = loadOwnAccountInfo(socialAccountId);
        String currentAccountName = account != null ? account.accountName() : null;

        List<AccountPostRow> posts = loadExistingAccountPosts(socialAccountId, currentAccountName, ACCOUNT_DNA_POST_LIMIT);
        if (posts.isEmpty()) {
            posts = fetchFreshAccountPosts(account, ACCOUNT_DNA_POST_LIMIT);
        }
        if (posts.isEmpty()) {
            log.info("Hesabın hiç gönderisi bulunamadı; DNA'sız devam ediliyor: socialAccountId={}", socialAccountId);
            return null;
        }

        String postsContext = buildPostsContext(posts);
        String visualPatterns = buildVisualPatterns(posts);
        String prompt = ContentPrompts.forBrandDna(postsContext, visualPatterns, sectorContext);
        String dna = aiAnalysisService.generateBrandDna(prompt);
        if (dna == null) {
            log.info("Hesap DNA üretilemedi (AI kapalı veya hata); atlanıyor: socialAccountId={}", socialAccountId);
            return null;
        }

        saveOrReactivateAccountDna(userId, socialAccountId, dna, posts.size());
        return dna;
    }

    /**
     * Üretilen DNA'yı cache'e yazar. user_account_dna(user_id, social_account_id) UNIQUE
     * olduğundan ve invalidateAccountDnaCache satırı SİLMEYİP yalnızca active=0 yaptığından
     * (bkz. AccountDnaCacheService), burada düz INSERT yapılırsa pasif satırla çakışıp unique
     * constraint ihlali oluşur — bu yüzden önce var olan satır (aktif/pasif fark etmez) aranır,
     * varsa güncellenir (reaktive edilir), yoksa yeni satır eklenir.
     */
    private void saveOrReactivateAccountDna(UUID userId, UUID socialAccountId, String dna, int postCount) {
        LocalDateTime now = LocalDateTime.now();
        try {
            String existingIdSql = """
                    SELECT user_account_dna_id FROM user_account_dna
                    WHERE user_id = ? AND social_account_id = ?
                    """;
            List<UUID> existing = jdbcTemplate.queryForList(existingIdSql, UUID.class, userId, socialAccountId);
            if (!existing.isEmpty()) {
                jdbcTemplate.update("""
                        UPDATE user_account_dna
                        SET dna_json = ?, source_post_count = ?, active = 1, updated_date = ?
                        WHERE user_account_dna_id = ?
                        """, dna, postCount, now, existing.get(0));
            } else {
                UserAccountDna row = new UserAccountDna();
                row.setUserAccountDnaId(UUID.randomUUID());
                row.setUserId(userId);
                row.setSocialAccountId(socialAccountId);
                row.setDnaJson(dna);
                row.setSourcePostCount(postCount);
                row.setActive((short) 1);
                row.setCreatedDate(now);
                row.setUpdatedDate(now);
                userAccountDnaRepository.save(row);
            }
            log.info("Hesap DNA üretildi ve cache'lendi: userId={}, socialAccountId={}, postSayısı={}",
                    userId, socialAccountId, postCount);
        } catch (Exception ex) {
            // Cache'e yazım başarısız olsa bile bu üretimde DNA kullanılmaya devam eder;
            // yalnızca bir sonraki üretimde tekrar hesaplanır (maliyet, veri kaybı değil).
            log.warn("Hesap DNA cache'e yazılamadı: socialAccountId={}, hata={}", socialAccountId, ex.getMessage());
        }
    }

    private String loadCachedAccountDna(UUID userId, UUID socialAccountId) {
        String sql = """
                SELECT dna_json FROM user_account_dna
                WHERE user_id = ? AND social_account_id = ? AND active = 1
                """;
        try {
            List<String> rows = jdbcTemplate.queryForList(sql, String.class, userId, socialAccountId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception ex) {
            log.warn("Hesap DNA cache sorgusu başarısız: socialAccountId={}, hata={}", socialAccountId, ex.getMessage());
            return null;
        }
    }

    /**
     * Hesabın DB'de zaten scrape edilmiş son N "OWN" gönderisini (caption + varsa görsel analiz)
     * döner. Belirli bir rapora değil, kullanıcının geçmişteki herhangi bir rapor isteğinde bu
     * hesap seçiliyken çekilmiş postlara bakar (report_request.selected_user_social_account_id).
     * rr.own_account_name = accountName filtresi, kullanıcı hesap adını değiştirdiğinde ESKİ
     * hesap adına ait postların yeni DNA üretimine sızmasını engeller (own_account_name o raporun
     * OLUŞTURULDUĞU ANDAKİ hesap adı snapshot'ıdır). accountName null ise (hesap bulunamadıysa)
     * hiçbir satır eşleşmez — fetchFreshAccountPosts fallback'i zaten güncel hesaptan çeker.
     */
    private List<AccountPostRow> loadExistingAccountPosts(UUID socialAccountId, String accountName, int limit) {
        String sql = """
                SELECT sp.caption, pa.analysis_json
                FROM social_post sp
                JOIN report_request rr ON rr.request_id = sp.request_id
                LEFT JOIN post_analysis pa ON pa.social_post_id = sp.social_post_id
                WHERE rr.selected_user_social_account_id = ?
                  AND sp.source_type = 'OWN'
                  AND rr.own_account_name = ?
                ORDER BY sp.post_date DESC
                LIMIT ?
                """;
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) ->
                    new AccountPostRow(rs.getString("caption"), rs.getString("analysis_json")),
                    socialAccountId, accountName, limit);
        } catch (Exception ex) {
            log.warn("Hesabın mevcut gönderileri yüklenemedi: socialAccountId={}, hata={}", socialAccountId, ex.getMessage());
            return List.of();
        }
    }

    private OwnAccountInfo loadOwnAccountInfo(UUID socialAccountId) {
        String sql = """
                SELECT platform, account_name, profile_url
                FROM user_social_account
                WHERE user_social_account_id = ? AND active = 1
                """;
        List<OwnAccountInfo> rows = jdbcTemplate.query(sql, (rs, rowNum) ->
                new OwnAccountInfo(rs.getString("platform"), rs.getString("account_name"), rs.getString("profile_url")),
                socialAccountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * DB'de hiç gönderisi bulunamayan hesap için Apify'dan taze son N gönderi çeker. Rapor
     * pipeline'ının aksine bu gönderiler social_post'a YAZILMAZ (spec kapsam dışı — yeni bir
     * persistence akışı eklemek; social_post.request_id NOT NULL olduğundan rapor'suz satır açmak
     * şema değişikliği gerektirir). Bunun yerine geçici (persist edilmeyen) SocialPost nesneleri
     * kurulup rapor pipeline'ının kullandığı aynı AiAnalysisService.analyzeFull(...) ile hem metrik
     * hem görsel analiz üretilir; sonuç yalnızca bu DNA üretimi için bellekte kullanılır.
     */
    private List<AccountPostRow> fetchFreshAccountPosts(OwnAccountInfo account, int limit) {
        if (account == null || account.profileUrl() == null || account.profileUrl().isBlank()) {
            return List.of();
        }
        List<ApifyPost> fetched = apifyClient.fetchPostsByUrls(List.of(account.profileUrl()), limit);
        List<AccountPostRow> rows = new ArrayList<>();
        for (ApifyPost post : fetched) {
            if (rows.size() >= limit) break;
            SocialPost transientPost = new SocialPost();
            transientPost.setCaption(post.caption());
            transientPost.setMediaUrl(post.mediaUrl());
            transientPost.setMediaType(post.mediaType());
            transientPost.setResultJson(post.rawJson());
            String analysisJson = aiAnalysisService.analyzeFull(transientPost);
            rows.add(new AccountPostRow(post.caption(), analysisJson));
        }
        return rows;
    }

    // Post caption'larını "Post 1: ..." formatında birleştirir
    private String buildPostsContext(List<AccountPostRow> posts) {
        List<String> captions = posts.stream()
                .map(AccountPostRow::caption)
                .filter(c -> c != null && !c.isBlank())
                .toList();
        if (captions.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < captions.size(); i++) {
            sb.append("Post ").append(i + 1).append(": ").append(captions.get(i)).append("\n");
        }
        return sb.toString();
    }

    /**
     * Görsel analiz JSON'larından (analyzeFull -> "visual" alanı) ürün kategorisi, atmosfer,
     * renk ve çekim stili özetini çeker. Bu akışta tüm postlar kullanıcının kendi hesabına ait
     * olduğundan (OWN) her satır [KENDİ] etiketiyle işaretlenir.
     */
    private String buildVisualPatterns(List<AccountPostRow> posts) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (AccountPostRow row : posts) {
            if (row.analysisJson() == null || row.analysisJson().isBlank()) continue;
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
                String visualThemes = arrayField(visual, "visualThemes");
                String sceneDescription = textField(visual, "sceneDescription");

                // En az bir değer varsa ekle
                if (productCategory == null && specificProduct == null && atmosphere == null) continue;

                count++;
                sb.append("Görsel ").append(count).append(" [KENDİ]: ");
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
                log.warn("Hesap görsel analiz satırı ayrıştırılamadı; atlanıyor: hata={}", ex.getMessage());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
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
            String sectorContext, String productContext, String productImageData,
            List<String> previousVisualUrls) {
        ContentType type = req.getContentType();
        List<String> urls = new ArrayList<>();
        String editInstruction = req.getEditInstruction();

        if (type == ContentType.CAROUSEL) {
            String[] roles = {"HOOK", "CONTENT", "CTA"};
            for (int i = 0; i < roles.length; i++) {
                String prompt = ContentPrompts.forVisual(brandDna, "CAROUSEL", i, roles[i],
                        req.isIncludeTextInVisual(), editInstruction, sectorContext, productContext, req.getVisualStyle());
                String referenceImage = editReferenceFor(previousVisualUrls, i, productImageData);
                String url = generateAndUpload(req, prompt, i, referenceImage);
                if (url != null) urls.add(url);
            }
            return new VisualResult(urls, roles.length);
        } else if (type == ContentType.REEL) {
            String prompt = ContentPrompts.forVideo(brandDna, editInstruction, sectorContext, productContext);
            String url = generateAndUploadVideo(req, prompt, editInstruction, productImageData);
            if (url != null) urls.add(url);
            return new VisualResult(urls, 1);
        } else {
            String prompt = ContentPrompts.forVisual(brandDna, type.name(), 0, null,
                    req.isIncludeTextInVisual(), editInstruction, sectorContext, productContext, req.getVisualStyle());
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
                String imagePrompt = ContentPrompts.forVisual(null, "REEL", 0, null, false, editInstruction, null, null, req.getVisualStyle());
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

    private void applyContentMetadata(ContentRequest req, String brandDna) {
        String prompt = ContentPrompts.forContentMetadata(brandDna, req.getContentType().name());
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
        // V11 — ücretsiz ilk kullanım: gerçek kredi düşümü hiç denenmez, doğrudan "başarıyla
        // düşüldü" (0 kredi, hiç harcanmadı) olarak işaretlenir — aksi halde reconciliation
        // bunu sonsuza kadar "başarısız düşüm" sanıp tekrar tekrar dener.
        if (req.getIsFreeUsage() == 1) {
            markCreditDebitState(contentRequestId, true, null);
            log.info("COMPLETED — ücretsiz ilk kullanım, kredi düşülmedi: contentRequestId={}", contentRequestId);
            return true;
        }
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
