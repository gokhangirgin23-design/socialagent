package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import com.api.ai.AiAnalysisService;
import com.api.entity.SocialPost;

/**
 * AnalysisPipelineService için Spring'siz birim testi (DB/AI gerektirmez).
 * Doğrulanan davranışlar (WorkerPrompt revizyonu):
 *  - analyzeJob: her analiz edilmemiş post için analyzeFull + saveAnalysis çağrılır.
 *  - analyzeJob: analiz edilecek post yoksa AI hiç çağrılmaz (idempotent).
 *  - analyzeJob: yalnızca başarıyla yazılan analizler sayılır.
 */
class AnalysisPipelineServiceTest {

	private JdbcTemplate jdbcTemplate;
	private AiAnalysisService aiAnalysisService;
	private PostAnalysisService postAnalysisService;
	private AnalysisPipelineService service;

	private final UUID jobId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		aiAnalysisService = org.mockito.Mockito.mock(AiAnalysisService.class);
		postAnalysisService = org.mockito.Mockito.mock(PostAnalysisService.class);
		service = new AnalysisPipelineService(jdbcTemplate, aiAnalysisService, postAnalysisService);
	}

	@SuppressWarnings("unchecked")
	@Test
	void herAnalizEdilmemisPostIcinAnalizCalisir() {
		SocialPost p1 = postWithId();
		SocialPost p2 = postWithId();
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(p1, p2));
		// analyzeFull: OpenAI + Gemini birleşik JSON
		when(aiAnalysisService.analyzeFull(any(SocialPost.class)))
				.thenReturn("{\"metrics\":{},\"visual\":{}}");
		when(postAnalysisService.saveAnalysis(any(UUID.class), anyString())).thenReturn(true);

		int analyzed = service.analyzeJob(jobId);

		verify(aiAnalysisService, times(2)).analyzeFull(any(SocialPost.class));
		verify(postAnalysisService, times(1)).saveAnalysis(eq(p1.getSocialPostId()), anyString());
		verify(postAnalysisService, times(1)).saveAnalysis(eq(p2.getSocialPostId()), anyString());
		assertEquals(2, analyzed);
	}

	@SuppressWarnings("unchecked")
	@Test
	void analizEdilecekPostYoksaAiCagrilmaz() {
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		int analyzed = service.analyzeJob(jobId);

		verify(aiAnalysisService, never()).analyzeFull(any(SocialPost.class));
		verify(postAnalysisService, never()).saveAnalysis(any(UUID.class), anyString());
		assertEquals(0, analyzed);
	}

	@SuppressWarnings("unchecked")
	@Test
	void yalnizcaYazilanAnalizlerSayilir() {
		SocialPost p1 = postWithId();
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(p1));
		when(aiAnalysisService.analyzeFull(any(SocialPost.class)))
				.thenReturn("{\"metrics\":{},\"visual\":{}}");
		// Yazma başarısız (zaten analizli) -> false
		when(postAnalysisService.saveAnalysis(any(UUID.class), anyString())).thenReturn(false);

		int analyzed = service.analyzeJob(jobId);

		verify(aiAnalysisService, times(1)).analyzeFull(any(SocialPost.class));
		assertEquals(0, analyzed);
	}

	private SocialPost postWithId() {
		SocialPost sp = new SocialPost();
		sp.setSocialPostId(UUID.randomUUID());
		sp.setPlatform("INSTAGRAM");
		sp.setMediaType("IMAGE");
		sp.setResultJson("{\"id\":\"abc\",\"likesCount\":100}");
		return sp;
	}
}
