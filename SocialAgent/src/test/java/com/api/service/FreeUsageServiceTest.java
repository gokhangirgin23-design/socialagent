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
 * V11 — Ücretsiz ilk kullanım hakkı (1 rapor + 1 post/story, Carousel hariç).
 * ICERIK-RAPOR-AYRISTIRMA-SPEC.md §2.4/§2.5 ile içerik hakkı rapor varlığından bağımsız hale
 * geldi — bu testler o davranışı doğrular (rapor üretmemiş kullanıcı da ücretsiz post/story
 * üretebilmeli). Spring'siz birim testi (DB gerektirmez, JdbcTemplate/repository mock'lanır).
 */
class FreeUsageServiceTest {

    private JdbcTemplate jdbcTemplate;
    private UserFreeUsageRepository repository;
    private FreeUsageService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = mock(UserFreeUsageRepository.class);
        service = new FreeUsageService(jdbcTemplate, repository);
    }

    @Test
    void carouselIcinUcretsizHakHicKullanilamaz() {
        // DB'ye hiç gidilmeden (repository çağrılmadan) false dönmeli — Carousel her zaman hariç
        assertFalse(service.isFreeContentAvailable(userId, ContentType.CAROUSEL));
        assertFalse(service.isFreeContentAvailable(userId, ContentType.REEL));
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
    void raporUretmemisKullaniciDaUcretsizPostStoryHakkiniKullanabilir() {
        // Spec: içerik üretimi rapordan bağımsız — free_report_request_id hiç set edilmemiş
        // (kullanıcı hiç rapor üretmemiş) olsa bile free_content_used=0 ise hak kullanılabilir.
        UserFreeUsage row = new UserFreeUsage();
        row.setUserId(userId);
        row.setFreeContentUsed(0);
        when(repository.findById(userId)).thenReturn(Optional.of(row));

        assertTrue(service.isFreeContentAvailable(userId, ContentType.POST));
        assertTrue(service.isFreeContentAvailable(userId, ContentType.STORY));
        // Rapor tablosuna hiç sorgu atılmadı
        verify(jdbcTemplate, never()).queryForObject(any(String.class), eq(Integer.class), any(), any());
    }

    @Test
    void icerikHakkiZatenKullanilmissaTekrarKullanilamaz() {
        UserFreeUsage row = new UserFreeUsage();
        row.setUserId(userId);
        row.setFreeContentUsed(1);
        when(repository.findById(userId)).thenReturn(Optional.of(row));
        assertFalse(service.isFreeContentAvailable(userId, ContentType.STORY));
    }

    @Test
    void satirYokkenIcerikHakkiKullanilamaz() {
        when(repository.findById(userId)).thenReturn(Optional.empty());
        assertFalse(service.isFreeContentAvailable(userId, ContentType.POST));
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
