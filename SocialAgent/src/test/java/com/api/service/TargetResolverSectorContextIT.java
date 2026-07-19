package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyProfile;
import com.api.config.AppProperties;
import com.api.entity.ReportRequest;

/**
 * TargetResolver.loadUserSectorContext'in GERÇEK SQL'i üzerinden regresyon testi — Mockito ile
 * mock'lanmış TargetResolverTest'in YAKALAYAMADIĞI bir prod insidenti: "FROM user_info ui, sector s
 * LEFT JOIN subsector ss ON ss.subsector_id = ui.subsector_id" Postgres'te "invalid reference to
 * FROM-clause entry for table ui" hatası veriyordu (JOIN, virgülden daha sıkı bağlanır; "sector s
 * LEFT JOIN subsector ss" kendi başına bir birim oluşturur, ON şartı henüz kapsama girmemiş "ui"ye
 * erişemez). Gerçek bir NONE raporu denemesiyle (test hesabı) canlıda bulundu — rapor FAILED oldu.
 * Bu test, jdbcTemplate.query'yi mock'lamak yerine gerçek H2 üzerinden çalıştırarak bir daha aynı
 * sınıf hatanın (SQL grammar) sessizce geçmesini engeller (bkz. ReportRequestServiceListRequestsIT
 * ile aynı desen — CLAUDE.md'deki "mock'lanmış testler DB constraint/syntax hatalarını yakalayamaz" dersi).
 *
 * @Transactional: her test sonunda ROLLBACK edilir, H2'ye kalıcı veri yazılmaz.
 */
@SpringBootTest
@Transactional
@DirtiesContext
class TargetResolverSectorContextIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    @Test
    void altSektoruOlanKullaniciIcinGercekSqlHatasizCalisir() {
        UUID userId = UUID.randomUUID();
        UUID sectorId = UUID.randomUUID();
        UUID subsectorId = UUID.randomUUID();
        insertSector(sectorId, "Moda-" + sectorId);
        insertSubsector(subsectorId, sectorId, "Takı");
        insertUser(userId, sectorId, subsectorId);

        ApifyClient apifyClient = mock(ApifyClient.class);
        when(apifyClient.searchTopProfiles(anyString(), anyInt())).thenReturn(List.of(
                new ApifyProfile("taki_hesap", "https://www.instagram.com/taki_hesap/", 1000, 0.1, null, null)));

        TargetResolver resolver = new TargetResolver(jdbcTemplate, apifyClient, appProperties);
        List<ScrapeTarget> targets = resolver.resolve(noneRequest(userId));

        assertEquals(1, targets.size());
        assertEquals(ScrapeTarget.TargetType.SECTOR, targets.get(0).type());
    }

    @Test
    void altSektoruOlmayanKullaniciIcinGercekSqlHatasizCalisir() {
        // Asıl kanıt: LEFT JOIN'in subsector_id NULL olan bir kullanıcıda da (subsector hiç
        // eklenmemiş) BadSqlGrammarException fırlatmadan tamamlanması.
        UUID userId = UUID.randomUUID();
        UUID sectorId = UUID.randomUUID();
        insertSector(sectorId, "E-Ticaret-" + sectorId);
        insertUser(userId, sectorId, null);

        ApifyClient apifyClient = mock(ApifyClient.class);
        when(apifyClient.searchTopProfiles(anyString(), anyInt())).thenReturn(List.of());

        TargetResolver resolver = new TargetResolver(jdbcTemplate, apifyClient, appProperties);
        List<ScrapeTarget> targets = resolver.resolve(noneRequest(userId));

        assertEquals(0, targets.size());
    }

    private ReportRequest noneRequest(UUID userId) {
        ReportRequest req = new ReportRequest();
        req.setRequestId(UUID.randomUUID());
        req.setUserId(userId);
        req.setReportType("NONE");
        return req;
    }

    private void insertSector(UUID sectorId, String name) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO sector (sector_id, name, active, created_date, updated_date) VALUES (?, ?, 1, ?, ?)",
                sectorId, name, now, now);
    }

    private void insertSubsector(UUID subsectorId, UUID sectorId, String name) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES (?, ?, ?, 1, ?, ?)",
                subsectorId, sectorId, name, now, now);
    }

    private void insertUser(UUID userId, UUID sectorId, UUID subsectorId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO user_info (user_id, google_id, email, sector_id, subsector_id, active, created_date, updated_date) " +
                        "VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                userId, "g-" + userId, userId + "@test.local", sectorId, subsectorId, now, now);
    }
}
