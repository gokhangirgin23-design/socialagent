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
 * JdbcTemplate, AiAnalysisService ve PostAnalysisService mock'lanır.
 * Doğrulanan davranışlar (FAZ 6):
 *  - analyzeJob: her analiz edilmemiş post için analyze + saveAnalysis çağrılır.
 *  - analyzeJob: analiz edilecek post yoksa AI hiç çağrılmaz (idempotent / boş tur).
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
		// @RequiredArgsConstructor sırası: jdbcTemplate, aiAnalysisService, postAnalysisService
		service = new AnalysisPipelineService(jdbcTemplate, aiAnalysisService, postAnalysisService);
	}

	@SuppressWarnings("unchecked")
	@Test
	void herAnalizEdilmemisPostIcinAnalizCalisir() {
		// İki analiz edilmemiş post dönsün
		SocialPost p1 = postWithId();
		SocialPost p2 = postWithId();
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(p1, p2));
		// AI her ikisi için de JSON üretsin
		when(aiAnalysisService.analyze(any(SocialPost.class))).thenReturn("{\"tone\":\"samimi\"}");
		// İkisi de başarıyla yazılsın
		when(postAnalysisService.saveAnalysis(any(UUID.class), anyString())).thenReturn(true);

		int analyzed = service.analyzeJob(jobId);

		// Her post için analyze + saveAnalysis çağrıldı, ikisi de sayıldı
		verify(aiAnalysisService, times(2)).analyze(any(SocialPost.class));
		verify(postAnalysisService, times(1)).saveAnalysis(eq(p1.getSocialPostId()), anyString());
		verify(postAnalysisService, times(1)).saveAnalysis(eq(p2.getSocialPostId()), anyString());
		assertEquals(2, analyzed);
	}

	@SuppressWarnings("unchecked")
	@Test
	void analizEdilecekPostYoksaAiCagrilmaz() {
		// Analiz edilmemiş post yok
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		int analyzed = service.analyzeJob(jobId);

		// Boş tur -> AI ve save hiç çağrılmaz
		verify(aiAnalysisService, never()).analyze(any(SocialPost.class));
		verify(postAnalysisService, never()).saveAnalysis(any(UUID.class), anyString());
		assertEquals(0, analyzed);
	}

	@SuppressWarnings("unchecked")
	@Test
	void yalnizcaYazilanAnalizlerSayilir() {
		// Bir post analiz edilecek
		SocialPost p1 = postWithId();
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(p1));
		// AI sonuç üretti ama (ör. zaten analizliydi) yazma başarısız -> false
		when(aiAnalysisService.analyze(any(SocialPost.class))).thenReturn("{\"tone\":\"samimi\"}");
		when(postAnalysisService.saveAnalysis(any(UUID.class), anyString())).thenReturn(false);

		int analyzed = service.analyzeJob(jobId);

		// AI çağrıldı ama yazılmadı -> sayım 0
		verify(aiAnalysisService, times(1)).analyze(any(SocialPost.class));
		assertEquals(0, analyzed);
	}

	// Test için id'li boş SocialPost üretir
	private SocialPost postWithId() {
		SocialPost sp = new SocialPost();
		sp.setSocialPostId(UUID.randomUUID());
		sp.setPlatform("INSTAGRAM");
		sp.setMediaType("TEXT");
		return sp;
	}
}
