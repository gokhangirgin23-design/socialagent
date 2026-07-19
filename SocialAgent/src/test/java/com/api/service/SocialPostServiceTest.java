package com.api.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.apify.ApifyPost;
import com.api.dto.repository.SocialPostRepository;
import com.api.entity.SocialPost;

/**
 * SocialPostService için Spring'siz birim testi (broker/DB gerektirmez).
 * Doğrulanan davranışlar:
 *  - isRecentlyAnalyzed: pencere içinde kayıt varsa true, yoksa false.
 *  - isRecentlyAnalyzed: SECTOR tipi her zaman false döner (her zaman taze çekilir).
 *  - saveRecentPosts: dedup boşsa save çağrılır (yeni gönderi); doluysa update çağrılır (mevcut gönderi).
 */
@SuppressWarnings("unchecked")
class SocialPostServiceTest {

    private JdbcTemplate jdbcTemplate;
    private SocialPostRepository socialPostRepository;
    private SocialPostService service;

    private final UUID ownAccountId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        socialPostRepository = org.mockito.Mockito.mock(SocialPostRepository.class);
        service = new SocialPostService(jdbcTemplate, socialPostRepository);
    }

    @Test
    void recentKayitVarsaTrueDoner() {
        // post_analysis JOIN sorgusu pencere içinde bir kayıt döndürsün
        // Farklı parametre tipleri (UUID + Timestamp) için esnek matcher
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of(UUID.randomUUID()));

        ScrapeTarget target = ScrapeTarget.own("INSTAGRAM", "kendi_hesap", ownAccountId);
        boolean recent = service.isRecentlyAnalyzed(target);

        assertTrue(recent);
    }

    @Test
    void recentKayitYoksaFalseDoner() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of());

        ScrapeTarget target = ScrapeTarget.own("INSTAGRAM", "kendi_hesap", ownAccountId);
        boolean recent = service.isRecentlyAnalyzed(target);

        assertFalse(recent);
    }

    @Test
    void ownSorgusuSourceTypeOwnFiltresiIcerir() {
        // KRİTİK regresyon: bu filtre olmadan, aynı isteğe bağlı SECTOR postları da eşleşip
        // OWN scraping'i hiç başarılı olmamış olsa bile "zaten analiz edilmiş" sanılıyordu —
        // gerçek vakada bulundu: bi_butik_originals'ın OWN scraping'i başarısız oldu ama 5
        // SECTOR postu analiz edildi; hesap mylovebutik olarak değiştirilip tekrar denendiğinde
        // bu sorgu o eski SECTOR postlarını "OWN analiz edilmiş" sayıp mylovebutik'i Apify'a
        // hiç göndermedi.
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of());

        ScrapeTarget target = ScrapeTarget.own("INSTAGRAM", "mylovebutik", UUID.randomUUID());
        service.isRecentlyAnalyzed(target);

        assertTrue(sqlCaptor.getValue().contains("source_type = 'OWN'"),
                "OWN sorgusu source_type='OWN' filtresi içermeli: " + sqlCaptor.getValue());
    }

    @Test
    void sectorTipiHerZamanFalseDoner() {
        // SECTOR tipi: sorgu hiç yapılmaz, her zaman false (her zaman taze çek)
        ScrapeTarget target = ScrapeTarget.sector("INSTAGRAM",
                "https://www.instagram.com/explore/tags/makyaj/");
        boolean recent = service.isRecentlyAnalyzed(target);

        assertFalse(recent);
        // JDBC'ye hiç gidilmemeli
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(), any());
    }

    @Test
    void yeniGonderilerKaydedilir() {
        // Dedup sorgusu boş -> tüm gönderiler yeni -> JPA save çağrılır
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), anyString()))
                .thenReturn(List.of());

        ScrapeTarget target = ScrapeTarget.own("INSTAGRAM", "kendi_hesap", ownAccountId);
        List<ApifyPost> posts = List.of(samplePost("p1"), samplePost("p2"));

        int inserted = service.saveRecentPosts(requestId, target, posts);

        verify(socialPostRepository, times(2)).save(any(SocialPost.class));
        assertTrue(inserted == 2);
    }

    @Test
    void mevcutGonderiSaveYerindeGuncellemeyiCagirirVeInsertSifirDoner() {
        // Dedup sorgusu dolu -> gönderi zaten kayıtlı -> JdbcTemplate.update ile güncellenir, save çağrılmaz
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), anyString()))
                .thenReturn(List.of(UUID.randomUUID()));

        ScrapeTarget target = ScrapeTarget.own("INSTAGRAM", "kendi_hesap", ownAccountId);
        List<ApifyPost> posts = List.of(samplePost("p1"));

        int inserted = service.saveRecentPosts(requestId, target, posts);

        // save çağrılmamalı (mevcut gönderi JdbcTemplate.update ile güncellenir)
        verify(socialPostRepository, never()).save(any(SocialPost.class));
        // update çağrılmalı (metrik güncelleme)
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), any(), any(), any(), any(), any(), any(), any(), anyString(), anyString());
        assertTrue(inserted == 0);
    }

    private ApifyPost samplePost(String postId) {
        return new ApifyPost(
                postId,
                "rakip_hesap",
                "https://instagram.com/p/" + postId,
                "örnek caption",
                "#a #b",
                "https://cdn/img.jpg",
                "IMAGE",
                100L, 10L, 0L, 0L,
                LocalDateTime.now(),
                "{\"id\":\"" + postId + "\",\"likesCount\":100}");
    }
}
