package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.api.entity.UserJob;

/**
 * TargetResolver için Spring'siz birim testi (WorkerPrompt revizyonu).
 * JdbcTemplate ve HashtagService mock'lanır.
 * Doğrulanan davranışlar:
 *  - COMPETITOR_ONLY: yalnızca rakip hesaplar; HashtagService ÇAĞRILMAZ.
 *  - NONE: HashtagService.resolveExploreUrls çağrılır ve SECTOR hedefi üretir.
 *  - OWN_ONLY: kendi hesap hedefi + HashtagService çağrılır.
 *  - BOTH: kendi + rakip hesaplar; HashtagService ÇAĞRILMAZ.
 */
class TargetResolverTest {

	private JdbcTemplate jdbcTemplate;
	private HashtagService hashtagService;
	private TargetResolver resolver;

	private final UUID userId = UUID.randomUUID();
	private final UUID jobId = UUID.randomUUID();
	private final UUID ownAccountId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		hashtagService = org.mockito.Mockito.mock(HashtagService.class);
		// @RequiredArgsConstructor sırası: jdbcTemplate, hashtagService
		resolver = new TargetResolver(jdbcTemplate, hashtagService);
	}

	@SuppressWarnings("unchecked")
	@Test
	void competitorOnlyHashtagServisiniCagirmaz() {
		// resolveMonitored sorgusu bir rakip hesap döndürsün
		ScrapeTarget rakip = ScrapeTarget.monitored("INSTAGRAM", "rakip1", UUID.randomUUID());
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(rakip));

		UserJob job = job("COMPETITOR_ONLY", null);
		List<ScrapeTarget> targets = resolver.resolve(job);

		assertEquals(1, targets.size());
		assertEquals(ScrapeTarget.TargetType.MONITORED, targets.get(0).type());
		// Sektör araştırması yok -> HashtagService çağrılmamalı
		verify(hashtagService, never()).resolveExploreUrls(any(UUID.class));
	}

	@SuppressWarnings("unchecked")
	@Test
	void noneModuHashtagServisindeSektorUrlUretir() {
		// HashtagService 2 explore URL döndürsün
		when(hashtagService.resolveExploreUrls(eq(userId)))
				.thenReturn(List.of(
						"https://www.instagram.com/explore/tags/makyaj/",
						"https://www.instagram.com/explore/tags/guzellik/"));

		UserJob job = job("NONE", null);
		List<ScrapeTarget> targets = resolver.resolve(job);

		// HashtagService bir kez çağrılmalı
		verify(hashtagService, times(1)).resolveExploreUrls(eq(userId));
		// 2 SECTOR hedef üretilmeli
		assertEquals(2, targets.size());
		targets.forEach(t -> assertEquals(ScrapeTarget.TargetType.SECTOR, t.type()));
	}

	@SuppressWarnings("unchecked")
	@Test
	void ownOnlyKendiHesapVeSektorHedefleriUretir() {
		// resolveOwn sorgusu OWN hedef döndürsün
		ScrapeTarget own = ScrapeTarget.own("INSTAGRAM", "kendi_hesap", ownAccountId);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(own));
		// HashtagService 1 URL döndürsün
		when(hashtagService.resolveExploreUrls(eq(userId)))
				.thenReturn(List.of("https://www.instagram.com/explore/tags/tekstil/"));

		UserJob job = job("OWN_ONLY", ownAccountId);
		List<ScrapeTarget> targets = resolver.resolve(job);

		// HashtagService çağrılmalı
		verify(hashtagService, times(1)).resolveExploreUrls(eq(userId));
		// Kendi hesabı (OWN) + 1 sektör hashtag URL'i (SECTOR) = 2 hedef
		assertEquals(2, targets.size());
		assertEquals(ScrapeTarget.TargetType.OWN, targets.get(0).type());
		assertEquals(ScrapeTarget.TargetType.SECTOR, targets.get(1).type());
	}

	@SuppressWarnings("unchecked")
	@Test
	void bothModuHashtagServisiniCagirmaz() {
		// resolveOwn + resolveMonitored sorguları dönüyor
		ScrapeTarget own = ScrapeTarget.own("INSTAGRAM", "kendi_hesap", ownAccountId);
		ScrapeTarget rakip = ScrapeTarget.monitored("INSTAGRAM", "rakip1", UUID.randomUUID());
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(own))
				.thenReturn(List.of(rakip));

		UserJob job = job("BOTH", ownAccountId);
		List<ScrapeTarget> targets = resolver.resolve(job);

		// HashtagService çağrılmamalı (BOTH'da sektör araştırması yok)
		verify(hashtagService, never()).resolveExploreUrls(any(UUID.class));
		// Kendi + rakip = 2 hedef
		assertEquals(2, targets.size());
	}

	// Test için örnek UserJob üretir
	private UserJob job(String mode, UUID selectedAccountId) {
		UserJob j = new UserJob();
		j.setUserJobId(jobId);
		j.setUserId(userId);
		j.setAnalysisMode(mode);
		j.setSelectedUserSocialAccountId(selectedAccountId);
		return j;
	}

	// eq() for UUID (Mockito ArgumentMatchers import)
	private static <T> T eq(T value) {
		return org.mockito.ArgumentMatchers.eq(value);
	}
}
