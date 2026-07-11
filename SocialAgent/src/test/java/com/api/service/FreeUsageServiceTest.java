package com.api.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.api.dto.repository.UserFreeUsageRepository;
import com.api.entity.ContentType;
import com.api.entity.UserFreeUsage;

/**
 * V11 — Ücretsiz ilk kullanım hakkı (1 rapor + sıralı bağlı 1 post/story, Carousel hariç).
 * Spring'siz birim testi (DB gerektirmez, JdbcTemplate/repository mock'lanır).
 */
class FreeUsageServiceTest {

    private JdbcTemplate jdbcTemplate;
    private UserFreeUsageRepository repository;
    private FreeUsageService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();
    private final UUID freeReportRequestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = mock(UserFreeUsageRepository.class);
        service = new FreeUsageService(jdbcTemplate, repository);
    }

    @Test
    void carouselIcinUcretsizHakHicKullanilamaz() {
        // DB'ye hiç gidilmeden (repository çağrılmadan) false dönmeli — Carousel her zaman hariç
        assertFalse(service.isFreeContentAvailable(userId, reportId, ContentType.CAROUSEL));
        assertFalse(service.isFreeContentAvailable(userId, reportId, ContentType.REEL));
        verify(repository, never()).findById(any());
    }

    @Test
    void satirYokkenRaporHakkiKullanilamaz() {
        when(repository.findById(userId)).thenReturn(Optional.empty());
        assertFalse(service.isFreeReportAvailable(userId));
    }

    @Test
    void hicKullanilmamisRaporHakkiMevcuttur() {
        UserFreeUsage row = new UserFreeUsage();
        row.setUserId(userId);
        row.setFreeReportUsed(0);
        when(repository.findById(userId)).thenReturn(Optional.of(row));
        assertTrue(service.isFreeReportAvailable(userId));
    }

    @Test
    void kullanilmisRaporHakkiTekrarKullanilamaz() {
        UserFreeUsage row = new UserFreeUsage();
        row.setUserId(userId);
        row.setFreeReportUsed(1);
        when(repository.findById(userId)).thenReturn(Optional.of(row));
        assertFalse(service.isFreeReportAvailable(userId));
    }

    @Test
    void raporHakkiHenuzKullanilmadiysaIcerikHakkiDaKullanilamaz() {
        // free_report_request_id null = kullanıcı hiç ücretsiz rapor üretmemiş — içerik hakkı
        // sıralı bağımlı olduğundan bu durumda da kullanılamaz
        UserFreeUsage row = new UserFreeUsage();
        row.setUserId(userId);
        when(repository.findById(userId)).thenReturn(Optional.of(row));
        assertFalse(service.isFreeContentAvailable(userId, reportId, ContentType.POST));
    }

    @Test
    void icerikHakkiZatenKullanilmissaTekrarKullanilamaz() {
        UserFreeUsage row = new UserFreeUsage();
        row.setUserId(userId);
        row.setFreeReportRequestId(freeReportRequestId);
        row.setFreeContentUsed(1);
        when(repository.findById(userId)).thenReturn(Optional.of(row));
        assertFalse(service.isFreeContentAvailable(userId, reportId, ContentType.STORY));
    }

    @SuppressWarnings("unchecked")
    @Test
    void farkliBirRapordanIcerikUcretsizUretilemez() {
        // free_report_request_id dolu ama sorgulanan reportId o rapora ait DEĞİL (COUNT=0)
        UserFreeUsage row = new UserFreeUsage();
        row.setUserId(userId);
        row.setFreeReportRequestId(freeReportRequestId);
        row.setFreeContentUsed(0);
        when(repository.findById(userId)).thenReturn(Optional.of(row));
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), any(), any()))
                .thenReturn(0);

        assertFalse(service.isFreeContentAvailable(userId, reportId, ContentType.POST));
    }

    @SuppressWarnings("unchecked")
    @Test
    void ucretsizUretilenRapordanPostVeyaStoryUretilebilir() {
        UserFreeUsage row = new UserFreeUsage();
        row.setUserId(userId);
        row.setFreeReportRequestId(freeReportRequestId);
        row.setFreeContentUsed(0);
        when(repository.findById(userId)).thenReturn(Optional.of(row));
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), any(), any()))
                .thenReturn(1);

        assertTrue(service.isFreeContentAvailable(userId, reportId, ContentType.POST));
        assertTrue(service.isFreeContentAvailable(userId, reportId, ContentType.STORY));
    }

    @Test
    void icerikHakkiAtomikTuketimeYarisKaybedenIkinciCagriBasarisizOlur() {
        // İlk çağrı: 1 satır etkilendi (hak tüketildi) — ikinci çağrı: 0 satır (zaten tüketilmiş)
        when(jdbcTemplate.update(any(String.class), any(), any(), any(), any())).thenReturn(1, 0);

        UUID contentRequestId1 = UUID.randomUUID();
        UUID contentRequestId2 = UUID.randomUUID();

        assertTrue(service.tryConsumeFreeContent(userId, contentRequestId1));
        assertFalse(service.tryConsumeFreeContent(userId, contentRequestId2));
    }

    @Test
    void kredisiOlmayanKullaniciHicSatinAlmamisSayilir() {
        when(jdbcTemplate.query(any(String.class), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(), eq(userId)))
                .thenReturn(java.util.List.of());
        assertFalse(service.hasEverPurchased(userId));
    }

    @Test
    void hicKredisiOlmayanAmaGecmisteSatinAlmisKullaniciTekrarSatinAlmisSayilir() {
        when(jdbcTemplate.query(any(String.class), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(), eq(userId)))
                .thenReturn(java.util.List.of(700L));
        assertTrue(service.hasEverPurchased(userId));
    }
}
