package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import com.api.entity.UserJob;

/**
 * TargetResolver için Spring'siz birim testi (broker/DB gerektirmez).
 * JdbcTemplate ve ApifyClient mock'lanır; AppProperties gerçek (varsayılan limitler).
 * Doğrulanan davranışlar:
 *  - COMPETITOR_ONLY: yalnızca rakip hesaplar; Apify profil araması ÇAĞRILMAZ.
 *  - NONE: sektör top-5 için Apify keyword araması bir kez çağrılır ve SECTOR hedefi üretir.
 */
class TargetResolverTest {

	// Mock bağımlılıklar
	private JdbcTemplate jdbcTemplate;
	private ApifyClient apifyClient;
	private AppProperties appProperties;
	private TargetResolver resolver;

	private final UUID userId = UUID.randomUUID();
	private final UUID jobId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		apifyClient = org.mockito.Mockito.mock(ApifyClient.class);
		// Gerçek AppProperties: getApify().getTopProfilesLimit() = 5
		appProperties = new AppProperties();
		// @RequiredArgsConstructor sırası: jdbcTemplate, apifyClient, appProperties
		resolver = new TargetResolver(jdbcTemplate, apifyClient, appProperties);
	}

	@SuppressWarnings("unchecked")
	@Test
	void competitorOnlyApifyAramaCagirmaz() {
		// resolveMonitored sorgusu bir rakip hesap döndürsün
		ScrapeTarget rakip = ScrapeTarget.monitored("INSTAGRAM", "rakip1", UUID.randomUUID());
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(rakip));

		UserJob job = job("COMPETITOR_ONLY", null);
		List<ScrapeTarget> targets = resolver.resolve(job);

		// Yalnızca rakip hedef; Apify profil araması yapılmamalı (maliyet önleme)
		assertEquals(1, targets.size());
		assertEquals(ScrapeTarget.TargetType.MONITORED, targets.get(0).type());
		verify(apifyClient, never()).searchTopProfiles(anyString(), anyInt());
	}

	@SuppressWarnings("unchecked")
	@Test
	void noneModuSektorTop5Ceker() {
		UUID sectorId = UUID.randomUUID();

		// 1) user_info lookup -> sector_id dolu, subsector_id null
		when(jdbcTemplate.query(contains("FROM user_info"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(new TargetResolver.SectorRef(sectorId, null)));
		// 2) sector adı lookup -> keyword
		when(jdbcTemplate.query(contains("FROM sector"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of("Tekstil"));
		// 3) Apify keyword araması -> bir profil
		when(apifyClient.searchTopProfiles(anyString(), anyInt()))
				.thenReturn(List.of(new ApifyProfile("hesap1", "https://x", 1000L, 2.5)));

		UserJob job = job("NONE", null);
		List<ScrapeTarget> targets = resolver.resolve(job);

		// Apify bir kez çağrılmalı; sonuç tek SECTOR hedefi
		verify(apifyClient, times(1)).searchTopProfiles(anyString(), anyInt());
		assertEquals(1, targets.size());
		assertEquals(ScrapeTarget.TargetType.SECTOR, targets.get(0).type());
		assertEquals("hesap1", targets.get(0).accountName());
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
}
