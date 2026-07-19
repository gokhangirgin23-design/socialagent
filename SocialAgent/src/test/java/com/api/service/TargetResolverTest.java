package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyProfile;
import com.api.config.AppProperties;
import com.api.entity.ReportRequest;

/**
 * TargetResolver için Spring'siz birim testi (BACKEND-TODO SORUN 1, madde 1.6).
 * jdbcTemplate.query vararg metodunu doReturn ile stub'larken tip güvenliği
 * sorunları nedeniyle @SuppressWarnings("unchecked") ve doReturn kullanılır.
 *
 * TargetResolver.SectorKeywordContext paket-private (bkz. sınıf yorumu) — bu testten
 * doğrudan oluşturulabilir.
 */
@SuppressWarnings("unchecked")
class TargetResolverTest {

    private JdbcTemplate jdbcTemplate;
    private ApifyClient apifyClient;
    private AppProperties appProperties;
    private TargetResolver resolver;

    private final UUID userId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID ownAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        apifyClient = org.mockito.Mockito.mock(ApifyClient.class);
        appProperties = org.mockito.Mockito.mock(AppProperties.class);

        AppProperties.Apify apifyCfg = org.mockito.Mockito.mock(AppProperties.Apify.class);
        when(appProperties.getApify()).thenReturn(apifyCfg);
        when(apifyCfg.getTopProfilesLimit()).thenReturn(5);
        // SORUN 1 varsayılanlarıyla aynı (AppProperties.Apify)
        when(apifyCfg.isSubsectorSearchEnabled()).thenReturn(true);
        when(apifyCfg.getSubsectorMinProfiles()).thenReturn(2);
        when(apifyCfg.getSubsectorPoolMultiplier()).thenReturn(2);

        resolver = new TargetResolver(jdbcTemplate, apifyClient, appProperties);
    }

    // ============================================================
    // SORUN 1, madde 1.1 — alt sektör öncelikli arama + fallback
    // ============================================================

    @Test
    void altSektorVarVeYeterliSonucVarsaSadeceAltSektorKullanilir() {
        // ctx: sektör=Moda, alt sektör=Takı — havuz (topN*multiplier=10) yeterli sayıda dönüyor
        // (>= subsectorMinProfiles=2), sektör fallback'i hiç tetiklenmemeli.
        doReturn(List.of(new TargetResolver.SectorKeywordContext("Moda", "Takı")))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        when(apifyClient.searchTopProfiles(eq("Takı"), eq(10))).thenReturn(List.of(
                new ApifyProfile("taki_hesap1", "https://www.instagram.com/taki_hesap1/", 50000, 0.05, null, null),
                new ApifyProfile("taki_hesap2", "https://www.instagram.com/taki_hesap2/", 40000, 0.04, null, null),
                new ApifyProfile("taki_hesap3", "https://www.instagram.com/taki_hesap3/", 30000, 0.03, null, null)));

        List<ScrapeTarget> targets = resolver.resolve(request("NONE", null));

        verify(apifyClient, times(1)).searchTopProfiles(eq("Takı"), eq(10));
        verify(apifyClient, never()).searchTopProfiles(eq("Moda"), anyInt());
        assertEquals(3, targets.size());
        targets.forEach(t -> assertEquals(ScrapeTarget.TargetType.SECTOR, t.type()));
    }

    @Test
    void altSektorVarAmaAzSonucVarsaSektorFallbackDevreyeGirerVeDedupeEdilir() {
        // Alt sektör havuzu 1 profil dönüyor (< subsectorMinProfiles=2) -> ana sektörle fallback.
        // "moda_ortak" her iki aramada da çıkıyor -> dedupe sonrası TEK kez sayılmalı.
        doReturn(List.of(new TargetResolver.SectorKeywordContext("Moda", "Lüks Moda")))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        when(apifyClient.searchTopProfiles(eq("Lüks Moda"), eq(10))).thenReturn(List.of(
                new ApifyProfile("moda_ortak", "https://www.instagram.com/moda_ortak/", 20000, 0.02, null, null)));
        when(apifyClient.searchTopProfiles(eq("Moda"), eq(10))).thenReturn(List.of(
                new ApifyProfile("moda_ortak", "https://www.instagram.com/moda_ortak/", 20000, 0.02, null, null),
                new ApifyProfile("moda_yeni", "https://www.instagram.com/moda_yeni/", 15000, 0.02, null, null)));

        List<ScrapeTarget> targets = resolver.resolve(request("NONE", null));

        verify(apifyClient, times(1)).searchTopProfiles(eq("Lüks Moda"), eq(10));
        verify(apifyClient, times(1)).searchTopProfiles(eq("Moda"), eq(10));
        assertEquals(2, targets.size(), "dedupe sonrası moda_ortak tek kez sayılmalı: " + targets);
        List<String> urls = targets.stream().map(ScrapeTarget::url).toList();
        assertTrue(urls.contains("https://www.instagram.com/moda_ortak/"));
        assertTrue(urls.contains("https://www.instagram.com/moda_yeni/"));
    }

    @Test
    void altSektorYoksaEskiDavranisKorunur() {
        // subsector_name NULL -> tek arama, topN (pool çarpanı YOK), relevance skorlaması yok.
        doReturn(List.of(new TargetResolver.SectorKeywordContext("Moda", null)))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        when(apifyClient.searchTopProfiles(eq("Moda"), eq(5))).thenReturn(List.of(
                new ApifyProfile("moda_hesap1", "https://www.instagram.com/moda_hesap1/", 100000, 0.05, null, null),
                new ApifyProfile("moda_hesap2", "https://www.instagram.com/moda_hesap2/", 80000, 0.04, null, null)));

        List<ScrapeTarget> targets = resolver.resolve(request("NONE", null));

        verify(apifyClient, times(1)).searchTopProfiles(eq("Moda"), eq(5));
        verify(apifyClient, never()).searchTopProfiles(anyString(), eq(10));
        assertEquals(2, targets.size());
    }

    @Test
    void altSektorAramasiDevreDisiysaSektorKullanilir() {
        when(appProperties.getApify().isSubsectorSearchEnabled()).thenReturn(false);
        doReturn(List.of(new TargetResolver.SectorKeywordContext("Moda", "Takı")))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        when(apifyClient.searchTopProfiles(eq("Moda"), eq(5))).thenReturn(List.of(
                new ApifyProfile("moda_hesap1", "https://www.instagram.com/moda_hesap1/", 100000, 0.05, null, null)));

        List<ScrapeTarget> targets = resolver.resolve(request("NONE", null));

        verify(apifyClient, never()).searchTopProfiles(eq("Takı"), anyInt());
        verify(apifyClient, times(1)).searchTopProfiles(eq("Moda"), eq(5));
        assertEquals(1, targets.size());
    }

    // ============================================================
    // SORUN 1, madde 1.2 — profil relevance skorlaması
    // ============================================================

    @Test
    void havuzTopNdenBuyukseAltSektorleEnCokOrtusenProfillerSecilir() {
        // topN=2 (bu test için override): havuzda 4 profil var, ikisi çok yüksek takipçili ama
        // alakasız, ikisi düşük takipçili ama fullName/biography'de "bebek" geçiyor (alt sektör=
        // "Bebek"). Relevance skorlaması takipçi sıralamasını EZMELI: seçilen 2 hedef alakalı olmalı.
        when(appProperties.getApify().getTopProfilesLimit()).thenReturn(2);
        doReturn(List.of(new TargetResolver.SectorKeywordContext("Bebek Ürünleri", "Bebek")))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        when(apifyClient.searchTopProfiles(eq("Bebek"), eq(4))).thenReturn(List.of(
                new ApifyProfile("megastore", "https://www.instagram.com/megastore/", 500000, 0.01, "Mega Store", null),
                new ApifyProfile("fashionhub", "https://www.instagram.com/fashionhub/", 300000, 0.02, "Fashion Hub", null),
                new ApifyProfile("kucukdostlar", "https://www.instagram.com/kucukdostlar/", 1000, 0.05, "Bebek Dünyası Butik", "Sevimli bebek kıyafetleri"),
                new ApifyProfile("minimoda", "https://www.instagram.com/minimoda/", 800, 0.06, "Mini Moda Butik", "Bebek ve çocuk giyim")));

        List<ScrapeTarget> targets = resolver.resolve(request("NONE", null));

        assertEquals(2, targets.size());
        List<String> urls = targets.stream().map(ScrapeTarget::url).toList();
        assertTrue(urls.contains("https://www.instagram.com/kucukdostlar/"), "Alt sektörle eşleşen profil seçilmeli: " + urls);
        assertTrue(urls.contains("https://www.instagram.com/minimoda/"), "Alt sektörle eşleşen profil seçilmeli: " + urls);
    }

    @Test
    void hicEslesmeYoksaElemesizIlkTopNAlinir() {
        // Havuzdaki hiçbir profilin fullName/biography/username'i alt sektör kelimeleriyle
        // örtüşmüyorsa (yanlış pozitif koruması) skor sıralaması yapılmadan ilk topN (5) alınır —
        // havuz topN'den büyük (6 profil) olmalı ki gerçekten kırpma/elemesiz-alma test edilsin.
        doReturn(List.of(new TargetResolver.SectorKeywordContext("Moda", "Vintage Koleksiyon")))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        when(apifyClient.searchTopProfiles(eq("Vintage Koleksiyon"), eq(10))).thenReturn(List.of(
                new ApifyProfile("h1", "https://www.instagram.com/h1/", 5000, 0.01, "Hesap Bir", null),
                new ApifyProfile("h2", "https://www.instagram.com/h2/", 4000, 0.01, "Hesap İki", null),
                new ApifyProfile("h3", "https://www.instagram.com/h3/", 3000, 0.01, "Hesap Üç", null),
                new ApifyProfile("h4", "https://www.instagram.com/h4/", 2000, 0.01, "Hesap Dört", null),
                new ApifyProfile("h5", "https://www.instagram.com/h5/", 1000, 0.01, "Hesap Beş", null),
                new ApifyProfile("h6", "https://www.instagram.com/h6/", 500, 0.01, "Hesap Altı", null)));

        List<ScrapeTarget> targets = resolver.resolve(request("NONE", null));

        assertEquals(5, targets.size(), "topN=5'e kırpılmalı: " + targets);
        List<String> urls = targets.stream().map(ScrapeTarget::url).toList();
        assertTrue(urls.contains("https://www.instagram.com/h1/"));
        assertTrue(!urls.contains("https://www.instagram.com/h6/"), "Orijinal sırada 6.'nın dışarıda kalması beklenir: " + urls);
    }

    @Test
    void ownOnlyKendiHesapVeSektorHedefleriUretir() {
        // resolveOwn sorgusu -> loadUserSectorContext sorgusu (2 çağrı sırayla; alt sektör yok)
        ScrapeTarget own = ScrapeTarget.own("INSTAGRAM", "kendi_hesap", ownAccountId);
        doReturn(List.of(own))
                .doReturn(List.of(new TargetResolver.SectorKeywordContext("Tekstil", null)))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        when(apifyClient.searchTopProfiles(eq("Tekstil"), eq(5))).thenReturn(List.of(
                new ApifyProfile("tekstil_profil", null, 50000, 0.03, null, null)));

        List<ScrapeTarget> targets = resolver.resolve(request("OWN_ONLY", ownAccountId));

        verify(apifyClient, times(1)).searchTopProfiles(eq("Tekstil"), eq(5));
        assertEquals(2, targets.size());
        assertEquals(ScrapeTarget.TargetType.OWN, targets.get(0).type());
        assertEquals(ScrapeTarget.TargetType.SECTOR, targets.get(1).type());
    }

    // ============================================================
    // sanitizeApifySearchKeyword — hem sektör hem alt sektöre uygulanır
    // ============================================================

    @Test
    void ozelKarakterliAltSektorAdiApifyeTemizlenmisGonderilir() {
        // Gerçek vaka: "&" Apify'ın arama aktörü tarafından 400 Bad Request ile reddediliyordu.
        // Artık alt sektör önceliğine geçildiği için sanitize alt sektöre de uygulanmalı.
        doReturn(List.of(new TargetResolver.SectorKeywordContext("Yeme & İçme", "Fast Food & Büfe")))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));
        when(apifyClient.searchTopProfiles(anyString(), anyInt())).thenReturn(List.of());

        resolver.resolve(request("NONE", null));

        verify(apifyClient, times(1)).searchTopProfiles(eq("Fast Food ve Büfe"), anyInt());
    }

    @Test
    void ozelKarakterliSektorAdiFallbacktteDeTemizlenmisGonderilir() {
        doReturn(List.of(new TargetResolver.SectorKeywordContext("Yeme & İçme", "Az Bilinen Tür")))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));
        // Alt sektör havuzu yetersiz -> sektör fallback tetiklenir
        when(apifyClient.searchTopProfiles(eq("Az Bilinen Tür"), anyInt())).thenReturn(List.of());
        when(apifyClient.searchTopProfiles(eq("Yeme ve İçme"), anyInt())).thenReturn(List.of());

        resolver.resolve(request("NONE", null));

        verify(apifyClient, times(1)).searchTopProfiles(eq("Yeme ve İçme"), anyInt());
    }

    private ReportRequest request(String reportType, UUID selectedAccountId) {
        ReportRequest r = new ReportRequest();
        r.setRequestId(requestId);
        r.setUserId(userId);
        r.setReportType(reportType);
        r.setSelectedUserSocialAccountId(selectedAccountId);
        return r;
    }
}
