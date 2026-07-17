package com.api.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyProfile;
import com.api.config.AppProperties;
import com.api.entity.AnalysisMode;
import com.api.entity.Platform;
import com.api.entity.ReportRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bir rapor isteği için report_type'a göre çekilecek hedef listesini belirler.
 * Service interface yok (CLAUDE.md Madde 1); concrete @Component.
 *
 * | Tür              | Hedefler                                                              |
 * | NONE             | Sektör keyword → Apify top N profil araması → profil URL'leri (D1)   |
 * | OWN_ONLY         | Kendi hesabı + sektör profil URL'leri (D1)                           |
 *
 * Geliştirme (rakip hesap özelliğinin kaldırılması): COMPETITOR_ONLY/BOTH modları ve MONITORED
 * hedef tipi tamamen kaldırıldı — resolve() artık yalnızca NONE/OWN_ONLY'yi işler.
 *
 * Lookup'lar JdbcTemplate native; ilişkili tablolar eski stil "=" join (CLAUDE.md Madde 6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetResolver {

    // Native sorgular için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // D1: sektör keyword araması (profil-bazlı; hashtag explore yerine)
    private final ApifyClient apifyClient;

    // top-profiles-limit config ayarı için
    private final AppProperties appProperties;

    /**
     * Rapor isteğinin tipine göre hedef listesini üretir.
     *
     * @param request işlenecek rapor isteği
     * @return çekilecek hedefler (boş olabilir)
     */
    public List<ScrapeTarget> resolve(ReportRequest request) {
        AnalysisMode mode = parseMode(request.getReportType());
        List<ScrapeTarget> targets = new ArrayList<>();

        switch (mode) {
            case NONE -> {
                // D1: Apify keyword araması → sektörün top N profili → SECTOR hedefler
                targets.addAll(resolveSectorByProfiles(request.getUserId()));
            }
            case OWN_ONLY -> {
                // Önce kendi hesabı, sonra D1 sektör profilleri
                addIfPresent(targets, resolveOwn(request.getSelectedUserSocialAccountId()));
                targets.addAll(resolveSectorByProfiles(request.getUserId()));
            }
        }

        log.info("Hedefler çözüldü: requestId={}, mod={}, hedefSayısı={}",
                request.getRequestId(), mode, targets.size());
        return targets;
    }

    // ============================================================
    // Mod bileşenleri
    // ============================================================

    /**
     * Sektör araştırması (D1 — CLAUDE.md Bölüm 10, SORUN 1 ile alt sektör önceliğine geçildi):
     * Apify keyword aramasıyla sektörün top N Instagram profilini bulur;
     * her profil için profil URL'si SECTOR ScrapeTarget'a dönüştürülür.
     * Hashtag explore URL yaklaşımı yerine D1'in öngördüğü profil araması kullanılır.
     *
     * Arama stratejisi (SORUN 1, madde 1.1):
     *   1. Alt sektör varsa: önce alt sektör keyword'üyle geniş bir havuz (topN * çarpan) çekilir.
     *   2. Havuz eşiğin (subsectorMinProfiles) altındaysa ana sektör keyword'üyle ikinci bir
     *      arama yapılıp sonuçlar accountName bazında dedupe edilerek birleştirilir (fallback).
     *   3. Havuz, relevance skorlamasından (madde 1.2) geçirilip en iyi topN seçilir.
     *   4. Alt sektör yoksa (veya devre dışıysa) eski davranış aynen korunur: tek arama, skorlama yok.
     */
    private List<ScrapeTarget> resolveSectorByProfiles(UUID userId) {
        SectorKeywordContext ctx = loadUserSectorContext(userId);
        AppProperties.Apify apifyCfg = appProperties.getApify();
        int topN = apifyCfg.getTopProfilesLimit();

        List<ApifyProfile> profiles;
        String effectiveKeyword;

        if (apifyCfg.isSubsectorSearchEnabled() && ctx.subsectorKeyword() != null) {
            int poolSize = topN * Math.max(apifyCfg.getSubsectorPoolMultiplier(), 1);
            List<ApifyProfile> subsectorProfiles = apifyClient.searchTopProfiles(ctx.subsectorKeyword(), poolSize);

            List<ApifyProfile> pool = subsectorProfiles;
            effectiveKeyword = ctx.subsectorKeyword();
            if (subsectorProfiles.size() < apifyCfg.getSubsectorMinProfiles() && ctx.sectorKeyword() != null) {
                List<ApifyProfile> sectorProfiles = apifyClient.searchTopProfiles(ctx.sectorKeyword(), poolSize);
                pool = mergeDedupeByAccountName(subsectorProfiles, sectorProfiles);
                effectiveKeyword = ctx.subsectorKeyword() + " (+fallback: " + ctx.sectorKeyword() + ")";
                log.info("Alt sektör havuzu yetersiz ({} < {}), sektör fallback devrede: alt={}, sektör={}, birlesikHavuz={}",
                        subsectorProfiles.size(), apifyCfg.getSubsectorMinProfiles(), ctx.subsectorKeyword(), ctx.sectorKeyword(), pool.size());
            }
            profiles = rankByRelevance(pool, ctx.subsectorKeyword(), topN);
        } else if (ctx.sectorKeyword() != null) {
            // Alt sektör yok/devre dışı: eski davranış — tek arama, relevance skorlaması yok.
            profiles = apifyClient.searchTopProfiles(ctx.sectorKeyword(), topN);
            effectiveKeyword = ctx.sectorKeyword();
        } else {
            log.warn("Sektör keyword belirlenemedi, SECTOR hedef oluşturulamadı: userId={}", userId);
            return List.of();
        }

        if (profiles.isEmpty()) {
            log.warn("Apify sektör profil araması boş döndü: keyword={}", effectiveKeyword);
            return List.of();
        }
        // Her profil için Instagram profil URL'si ile SECTOR hedef oluştur
        List<ScrapeTarget> targets = new ArrayList<>();
        for (ApifyProfile profile : profiles) {
            String url = (profile.accountName() != null && !profile.accountName().isBlank())
                    ? "https://www.instagram.com/" + profile.accountName() + "/"
                    : profile.profileUrl();
            if (url == null || url.isBlank()) {
                continue;
            }
            targets.add(ScrapeTarget.sector(Platform.INSTAGRAM.name(), url));
        }
        log.info("Sektör profil hedefleri çözüldü: keyword={}, hedefSayısı={}", effectiveKeyword, targets.size());
        return targets;
    }

    /**
     * SORUN 1, madde 1.2 — profil relevance skorlaması (arama sonrası, AI çağrısı YOK).
     * Havuzdaki her profilin username/fullName/biography metinlerini alt sektör token'larıyla
     * (SectorRelevanceFilter.tokenize — Türkçe lowercase, 4+ karakter) kıyaslayıp örtüşen token
     * sayısını skor olarak kullanır; azalan skora göre sıralayıp en iyi topN'i döndürür.
     * Havuz zaten topN'den küçük/eşitse veya hiçbir profil eşleşme almazsa (skorların tamamı 0,
     * yanlış pozitif riski) elemesiz ilk topN döndürülür.
     */
    private List<ApifyProfile> rankByRelevance(List<ApifyProfile> pool, String subsectorKeyword, int topN) {
        if (pool.size() <= topN) {
            return pool;
        }
        Set<String> subsectorTokens = SectorRelevanceFilter.tokenize(subsectorKeyword);
        if (subsectorTokens.isEmpty()) {
            return new ArrayList<>(pool.subList(0, topN));
        }
        Map<ApifyProfile, Integer> scores = new LinkedHashMap<>();
        for (ApifyProfile profile : pool) {
            scores.put(profile, relevanceScore(profile, subsectorTokens));
        }
        boolean anyMatch = scores.values().stream().anyMatch(s -> s > 0);
        if (!anyMatch) {
            return new ArrayList<>(pool.subList(0, topN));
        }
        List<ApifyProfile> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingInt((ApifyProfile p) -> scores.get(p)).reversed());
        return new ArrayList<>(sorted.subList(0, topN));
    }

    private int relevanceScore(ApifyProfile profile, Set<String> subsectorTokens) {
        String text = String.join(" ",
                nullToEmpty(profile.accountName()),
                nullToEmpty(profile.fullName()),
                nullToEmpty(profile.biography()));
        Set<String> profileTokens = new java.util.HashSet<>(SectorRelevanceFilter.tokenize(text));
        profileTokens.retainAll(subsectorTokens);
        return profileTokens.size();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** accountName bazında dedupe eder; ilk listedeki (alt sektör) sonuçlar önceliklidir. */
    private List<ApifyProfile> mergeDedupeByAccountName(List<ApifyProfile> primary, List<ApifyProfile> secondary) {
        Map<String, ApifyProfile> byAccount = new LinkedHashMap<>();
        for (ApifyProfile p : primary) {
            byAccount.putIfAbsent(p.accountName(), p);
        }
        for (ApifyProfile p : secondary) {
            byAccount.putIfAbsent(p.accountName(), p);
        }
        return new ArrayList<>(byAccount.values());
    }

    /**
     * Sektör + (varsa) alt sektör adı — ikisi de Apify aramasına uygun şekilde sanitize edilmiştir.
     * Paket-private: TargetResolverTest aynı paketten (com.api.service) doğrudan erişir/oluşturur
     * (bkz. ReportPipelineService.PostRaw ile aynı desen).
     */
    record SectorKeywordContext(String sectorKeyword, String subsectorKeyword) {
    }

    /**
     * Kullanıcının sektör ve (varsa) alt sektör adını döndürür — Apify keyword araması için (D1, SORUN 1).
     *
     * SORUN 1 öncesi burada BİLEREK yalnızca sektör adı kullanılıyordu: Apify'ın Instagram araması
     * semantik değil, metin/kelime eşleştirmesine yakın çalıştığından "Lüks Moda" gibi dar, çok
     * kelimeli bir alt sektör ifadesi eşleşecek gerçekten konuyla ilgili hesap havuzunu küçültüp
     * alakasız hesapların (ör. kullanıcı adında tesadüfen aynı kelimeler geçen bir emlak hesabı)
     * ilk N sonuca sızma riskini artırıyordu — gerçek bir vakada bulundu. Kıyaslamanın ana sektöre
     * göre değil kullanıcının ALT sektörüne göre yapılması gerektiği için ("Moda/Takı" seçen
     * kullanıcıya genel moda hesaplarıyla değil takı hesaplarıyla kıyas yapılmalı), bu riske karşı
     * üç katmanlı güvence eklenip alt sektör arama önceliğine geri dönüldü:
     *   (a) alt sektör aramasının havuzu geniş tutulur (topN * çarpan), sonra en iyi topN seçilir;
     *   (b) havuz yetersizse ana sektörle fallback araması yapılıp birleştirilir;
     *   (c) relevance skorlaması (madde 1.2) havuzu alt sektör kelimeleriyle tekrar süzer.
     * Ek olarak SectorRelevanceFilter artık subsector-aware (madde 1.3) — arama sonrası kirlenmeyi
     * de ayrıca yakalar.
     */
    private SectorKeywordContext loadUserSectorContext(UUID userId) {
        // DİKKAT: "FROM user_info ui, sector s LEFT JOIN subsector ss ON ...ui..." (eski stil
        // virgül join + ardından LEFT JOIN) Postgres'te ÇALIŞMAZ — JOIN, virgülden daha sıkı
        // bağlandığından "sector s LEFT JOIN subsector ss" kendi başına bir birim oluşturur ve
        // ON şartı henüz kapsama girmemiş "ui"ye erişemez ("invalid reference to FROM-clause
        // entry for table ui"). Canlıda gerçek bir NONE/OWN_ONLY rapor denemesiyle bulundu —
        // TargetResolverTest jdbcTemplate.query'yi mock'ladığından bu gerçek SQL hatası hiç
        // çalıştırılmamıştı. Düzeltme: user_info BASE FROM tablosu, sector da (subsector gibi)
        // açık JOIN ile bağlanır — böylece "ui" iki JOIN'in ON şartından da erişilebilir kalır.
        String sql = """
                SELECT s.name AS sector_name, ss.name AS subsector_name
                FROM user_info ui
                JOIN sector s ON s.sector_id = ui.sector_id AND s.active = 1
                LEFT JOIN subsector ss ON ss.subsector_id = ui.subsector_id AND ss.active = 1
                WHERE ui.user_id = ?
                  AND ui.active = 1
                """;
        // Sanitize burada, RowMapper DIŞINDA uygulanır (DB'den ham isim çekilir) — RowMapper'ın
        // DB sürücüsü/ResultSet'e bağımlı olması testlerde jdbcTemplate.query'yi doğrudan
        // mock'lamayı zorlaştırırdı; ham → sanitize ayrımı ReportRequestService'teki eski stil
        // sanitize kullanım deseniyle de tutarlı.
        List<SectorKeywordContext> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new SectorKeywordContext(
                rs.getString("sector_name"), rs.getString("subsector_name")), userId);
        if (rows.isEmpty()) {
            return new SectorKeywordContext(null, null);
        }
        SectorKeywordContext raw = rows.get(0);
        return new SectorKeywordContext(
                sanitizeApifySearchKeyword(raw.sectorKeyword()),
                sanitizeApifySearchKeyword(raw.subsectorKeyword()));
    }

    /**
     * Apify'ın instagram-search-scraper aktörü, input.search alanında noktalama işaretlerine
     * (! ? . , : ; - + = * & % $ # @ / \ ~ ^ | &lt; &gt; ( ) [ ] { } " ' `) izin vermiyor — 400
     * Bad Request ile reddediyor. Gerçek bir vakada bulundu: "Yeme &amp; İçme" sektörü bu yüzden
     * HİÇ arama yapamadı (400 hatası sessizce boş sonuç olarak yutuldu), OWN_ONLY raporu yalnızca
     * KENDİ hesapla sınırlı kaldı. Şu an tutulan 16 sektörden 4'ü "&amp;" içeriyor (Yeme &amp; İçme,
     * Otel &amp; Turizm, Fotoğraf &amp; Video, Ev &amp; Yaşam) — bu yüzden tek bir sektöre özel yama değil,
     * genel bir sanitizer gerekiyor. DB'deki görünen isim (UI, Brand DNA bağlamı) DEĞİŞMEZ,
     * sadece Apify'a giden arama terimi temizlenir.
     */
    private static String sanitizeApifySearchKeyword(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace("&", " ve ");
        s = s.replaceAll("[!?.,:;\\-+=*%$#@/\\\\~^|<>()\\[\\]{}\"'`]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isBlank() ? null : s;
    }

    /**
     * Kendi (tek) hesabı OWN hedefine çevirir; hesap yoksa null.
     */
    private ScrapeTarget resolveOwn(UUID selectedUserSocialAccountId) {
        if (selectedUserSocialAccountId == null) {
            return null;
        }
        String sql = """
                SELECT platform, account_name
                FROM user_social_account
                WHERE user_social_account_id = ? AND active = 1
                """;
        List<ScrapeTarget> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> ScrapeTarget.own(
                        rs.getString("platform"),
                        rs.getString("account_name"),
                        selectedUserSocialAccountId),
                selectedUserSocialAccountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    private void addIfPresent(List<ScrapeTarget> targets, ScrapeTarget target) {
        if (target != null) {
            targets.add(target);
        }
    }

    private AnalysisMode parseMode(String value) {
        try {
            return AnalysisMode.valueOf(value);
        } catch (Exception e) {
            log.warn("Geçersiz reportType='{}', NONE varsayıldı.", value);
            return AnalysisMode.NONE;
        }
    }
}
