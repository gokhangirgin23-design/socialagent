package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
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
 * TargetResolver için Spring'siz birim testi.
 * jdbcTemplate.query vararg metodunu doReturn ile stub'larken tip güvenliği
 * sorunları nedeniyle @SuppressWarnings("unchecked") ve doReturn kullanılır.
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
    private final UUID sectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        apifyClient = org.mockito.Mockito.mock(ApifyClient.class);
        appProperties = org.mockito.Mockito.mock(AppProperties.class);

        AppProperties.Apify apifyCfg = org.mockito.Mockito.mock(AppProperties.Apify.class);
        when(appProperties.getApify()).thenReturn(apifyCfg);
        when(apifyCfg.getTopProfilesLimit()).thenReturn(5);

        resolver = new TargetResolver(jdbcTemplate, apifyClient, appProperties);
    }

    @Test
    void competitorOnlySektorAramasiYapilmaz() {
        // resolveMonitored sorgusu tek rakip döndürsün
        ScrapeTarget rakip = ScrapeTarget.monitored("INSTAGRAM", "rakip1", UUID.randomUUID());
        doReturn(List.of(rakip))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        ReportRequest req = request("COMPETITOR_ONLY", null);
        List<ScrapeTarget> targets = resolver.resolve(req);

        assertEquals(1, targets.size());
        assertEquals(ScrapeTarget.TargetType.MONITORED, targets.get(0).type());
        // COMPETITOR_ONLY -> sektör araması yapılmaz
        verify(apifyClient, never()).searchTopProfiles(anyString(), anyInt());
    }

    @Test
    void noneModuSektorProfillerindeScrapeTargetUretir() {
        // loadUserSectorKeyword: 1. user_info sorgusu -> 2. sector adı sorgusu
        // singletonList kullanılır: List.of(UUID[]) null element nedeniyle NPE atar
        UUID[] sectorRef = new UUID[]{ sectorId, null };
        doReturn(Collections.singletonList(sectorRef))
                .doReturn(List.of("Moda"))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        when(apifyClient.searchTopProfiles(anyString(), anyInt())).thenReturn(List.of(
                new ApifyProfile("moda_hesap1", "https://www.instagram.com/moda_hesap1/", 100000, 0.05),
                new ApifyProfile("moda_hesap2", "https://www.instagram.com/moda_hesap2/", 80000, 0.04)));

        List<ScrapeTarget> targets = resolver.resolve(request("NONE", null));

        verify(apifyClient, times(1)).searchTopProfiles(anyString(), anyInt());
        assertEquals(2, targets.size());
        targets.forEach(t -> assertEquals(ScrapeTarget.TargetType.SECTOR, t.type()));
    }

    @Test
    void ownOnlyKendiHesapVeSektorHedefleriUretir() {
        // resolveOwn sorgusu -> user_info sorgusu -> sector adı sorgusu (3 çağrı sırayla)
        ScrapeTarget own = ScrapeTarget.own("INSTAGRAM", "kendi_hesap", ownAccountId);
        // singletonList: UUID[] içindeki null subsectorId, List.of vararg expansion nedeniyle NPE yapar
        UUID[] sectorRef = new UUID[]{ sectorId, null };
        doReturn(List.of(own))
                .doReturn(Collections.singletonList(sectorRef))
                .doReturn(List.of("Tekstil"))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        when(apifyClient.searchTopProfiles(anyString(), anyInt())).thenReturn(List.of(
                new ApifyProfile("tekstil_profil", null, 50000, 0.03)));

        List<ScrapeTarget> targets = resolver.resolve(request("OWN_ONLY", ownAccountId));

        verify(apifyClient, times(1)).searchTopProfiles(anyString(), anyInt());
        assertEquals(2, targets.size());
        assertEquals(ScrapeTarget.TargetType.OWN, targets.get(0).type());
        assertEquals(ScrapeTarget.TargetType.SECTOR, targets.get(1).type());
    }

    @Test
    void bothModuSektorAramasiYapilmaz() {
        // resolveOwn + resolveMonitored (2 çağrı sırayla)
        ScrapeTarget own = ScrapeTarget.own("INSTAGRAM", "kendi_hesap", ownAccountId);
        ScrapeTarget rakip = ScrapeTarget.monitored("INSTAGRAM", "rakip1", UUID.randomUUID());
        doReturn(List.of(own))
                .doReturn(List.of(rakip))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(UUID.class));

        List<ScrapeTarget> targets = resolver.resolve(request("BOTH", ownAccountId));

        // BOTH -> sektör araması yapılmaz
        verify(apifyClient, never()).searchTopProfiles(anyString(), anyInt());
        assertEquals(2, targets.size());
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
