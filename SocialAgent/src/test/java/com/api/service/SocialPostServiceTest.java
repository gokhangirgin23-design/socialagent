package com.api.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * Doğrulanan davranışlar (WorkerPrompt revizyonu):
 *  - isRecentlyAnalyzed: pencere içinde kayıt varsa true, yoksa false.
 *  - isRecentlyAnalyzed: SECTOR tipi her zaman false döner (her zaman taze çekilir).
 *  - saveRecentPosts: dedup boşsa save çağrılır; doluysa (zaten kayıtlı) save çağrılmaz.
 */
class SocialPostServiceTest {

	private JdbcTemplate jdbcTemplate;
	private SocialPostRepository socialPostRepository;
	private SocialPostService service;

	private final UUID monitoredId = UUID.randomUUID();
	private final UUID jobId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		socialPostRepository = org.mockito.Mockito.mock(SocialPostRepository.class);
		service = new SocialPostService(jdbcTemplate, socialPostRepository);
	}

	@SuppressWarnings("unchecked")
	@Test
	void recentKayitVarsaTrueDoner() {
		// post_analysis JOIN sorgusu pencere içinde bir kayıt döndürsün
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(UUID.randomUUID()));

		ScrapeTarget target = ScrapeTarget.monitored("INSTAGRAM", "rakip_hesap", monitoredId);
		// imza: isRecentlyAnalyzed(ScrapeTarget) — sabit 30 gün
		boolean recent = service.isRecentlyAnalyzed(target);

		assertTrue(recent);
	}

	@SuppressWarnings("unchecked")
	@Test
	void recentKayitYoksaFalseDoner() {
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		ScrapeTarget target = ScrapeTarget.monitored("INSTAGRAM", "rakip_hesap", monitoredId);
		boolean recent = service.isRecentlyAnalyzed(target);

		assertFalse(recent);
	}

	@Test
	void sectorTipiHerZamanFalseDoner() {
		// SECTOR tipi: sorgu hiç yapılmaz, her zaman false (her zaman taze çek)
		ScrapeTarget target = ScrapeTarget.sector("INSTAGRAM",
				"https://www.instagram.com/explore/tags/makyaj/");
		boolean recent = service.isRecentlyAnalyzed(target);

		assertFalse(recent);
		// JDBC'ye hiç gidilmemeli
		verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), (Object[]) any());
	}

	@SuppressWarnings("unchecked")
	@Test
	void yeniGonderilerKaydedilir() {
		// Dedup sorgusu boş -> tüm gönderiler yeni
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		ScrapeTarget target = ScrapeTarget.monitored("INSTAGRAM", "rakip_hesap", monitoredId);
		List<ApifyPost> posts = List.of(samplePost("p1"), samplePost("p2"));

		int inserted = service.saveRecentPosts(jobId, target, posts);

		verify(socialPostRepository, times(2)).save(any(SocialPost.class));
		assertTrue(inserted == 2);
	}

	@SuppressWarnings("unchecked")
	@Test
	void zatenKayitliGonderiAtlanir() {
		// Dedup sorgusu dolu -> gönderi zaten kayıtlı
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(UUID.randomUUID()));

		ScrapeTarget target = ScrapeTarget.monitored("INSTAGRAM", "rakip_hesap", monitoredId);
		List<ApifyPost> posts = List.of(samplePost("p1"));

		int inserted = service.saveRecentPosts(jobId, target, posts);

		verify(socialPostRepository, never()).save(any(SocialPost.class));
		assertTrue(inserted == 0);
	}

	// ApifyPost: yeni imzayla (ownerUsername + rawJson eklendi)
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
