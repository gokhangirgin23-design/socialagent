package com.api.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import com.api.apify.ApifyClient;
import com.api.apify.ApifyPost;
import com.api.config.AppProperties;
import com.api.entity.UserJob;

/**
 * ScrapePipelineService için Spring'siz birim testi (broker/DB gerektirmez).
 * JdbcTemplate + TargetResolver + ApifyClient + SocialPostService mock'lanır; AppProperties gerçek.
 * Doğrulanan davranışlar:
 *  - Hedef yakın zamanda analiz EDİLMEMİŞSE: Apify'dan çekilir ve social_post'a yazılır.
 *  - Hedef yakın zamanda analiz EDİLMİŞSE: tekrar-analiz koruması Apify'ı atlar.
 */
class ScrapePipelineServiceTest {

	// Mock bağımlılıklar
	private JdbcTemplate jdbcTemplate;
	private TargetResolver targetResolver;
	private ApifyClient apifyClient;
	private SocialPostService socialPostService;
	private AnalysisPipelineService analysisPipelineService;
	private ReportPipelineService reportPipelineService;
	private NotificationService notificationService;
	private JobCompletionService jobCompletionService;
	private AppProperties appProperties;
	private ScrapePipelineService pipeline;

	private final UUID jobId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		targetResolver = org.mockito.Mockito.mock(TargetResolver.class);
		apifyClient = org.mockito.Mockito.mock(ApifyClient.class);
		socialPostService = org.mockito.Mockito.mock(SocialPostService.class);
		analysisPipelineService = org.mockito.Mockito.mock(AnalysisPipelineService.class);
		reportPipelineService = org.mockito.Mockito.mock(ReportPipelineService.class);
		notificationService = org.mockito.Mockito.mock(NotificationService.class);
		jobCompletionService = org.mockito.Mockito.mock(JobCompletionService.class);
		// Gerçek AppProperties: getApify().getRecentPostsLimit() = 5
		appProperties = new AppProperties();
		// @RequiredArgsConstructor sırası: jdbcTemplate, targetResolver, apifyClient, socialPostService,
		// analysisPipelineService, reportPipelineService, notificationService, jobCompletionService, appProperties
		pipeline = new ScrapePipelineService(jdbcTemplate, targetResolver, apifyClient, socialPostService,
				analysisPipelineService, reportPipelineService, notificationService, jobCompletionService,
				appProperties);
	}

	@SuppressWarnings("unchecked")
	@Test
	void analizEdilmemisHedefApifydanCekilirVeYazilir() {
		// loadJob -> aktif job (analysis_period_days = 7)
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(job(7)));
		// Mod çözümü -> tek hedef
		ScrapeTarget target = ScrapeTarget.monitored("INSTAGRAM", "rakip1", UUID.randomUUID());
		when(targetResolver.resolve(any(UserJob.class))).thenReturn(List.of(target));
		// Tekrar-analiz koruması -> hayır, analiz yok
		when(socialPostService.isRecentlyAnalyzed(any(ScrapeTarget.class), anyInt())).thenReturn(false);
		// Apify -> bir gönderi
		when(apifyClient.fetchRecentPosts(anyString(), anyInt())).thenReturn(List.of(samplePost("p1")));
		// FAZ 8: rapor COMPLETED -> bildirim tetiklenmeli
		when(reportPipelineService.generateReport(eq(jobId))).thenReturn(true);

		pipeline.processJob(jobId);

		// Apify çekilmeli ve social_post'a yazılmalı
		verify(apifyClient, times(1)).fetchRecentPosts(eq("rakip1"), anyInt());
		verify(socialPostService, times(1)).saveRecentPosts(eq(jobId), eq(target), any());
		// FAZ 7: analiz + rapor + iş sonu muhasebesi zinciri tetiklenmeli
		verify(analysisPipelineService, times(1)).analyzeJob(eq(jobId));
		verify(reportPipelineService, times(1)).generateReport(eq(jobId));
		verify(jobCompletionService, times(1)).finalizeJob(eq(jobId));
		// FAZ 8: rapor tamamlandığından bildirim gönderilmeli
		verify(notificationService, times(1)).notifyReportCompleted(eq(jobId));
	}

	@SuppressWarnings("unchecked")
	@Test
	void yakinZamandaAnalizEdilenHedefApifyAtlar() {
		// loadJob -> aktif job
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(job(7)));
		// Mod çözümü -> tek hedef
		ScrapeTarget target = ScrapeTarget.monitored("INSTAGRAM", "rakip1", UUID.randomUUID());
		when(targetResolver.resolve(any(UserJob.class))).thenReturn(List.of(target));
		// Tekrar-analiz koruması -> evet, son pencerede analiz edilmiş
		when(socialPostService.isRecentlyAnalyzed(any(ScrapeTarget.class), anyInt())).thenReturn(true);

		pipeline.processJob(jobId);

		// Apify'a hiç gidilmemeli ve yazma yapılmamalı
		verify(apifyClient, never()).fetchRecentPosts(anyString(), anyInt());
		verify(socialPostService, never()).saveRecentPosts(any(), any(), any());
		// FAZ 8: rapor tamamlanmadığından (generateReport default false) bildirim de gönderilmemeli
		verify(notificationService, never()).notifyReportCompleted(any());
	}

	// Test için örnek UserJob üretir
	private UserJob job(int periodDays) {
		UserJob j = new UserJob();
		j.setUserJobId(jobId);
		j.setUserId(UUID.randomUUID());
		j.setAnalysisMode("COMPETITOR_ONLY");
		j.setAnalysisPeriodDays(periodDays);
		return j;
	}

	// Test için örnek ApifyPost üretir
	private ApifyPost samplePost(String postId) {
		return new ApifyPost(
				postId,
				"https://instagram.com/p/" + postId,
				"örnek caption",
				"#a #b",
				"https://cdn/img.jpg",
				"IMAGE",
				100L, 10L, 0L, 0L,
				LocalDateTime.now());
	}
}
