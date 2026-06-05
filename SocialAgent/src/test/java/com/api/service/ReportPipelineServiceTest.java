package com.api.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.api.dto.ReportPostRow;

/**
 * ReportPipelineService için Spring'siz birim testi (DB/AI gerektirmez).
 * JdbcTemplate, AiAnalysisService ve ReportService mock'lanır.
 * Doğrulanan davranışlar (FAZ 7):
 *  - Analiz yoksa rapor üretilmez (AI ve ensureReport hiç çağrılmaz; false).
 *  - Analiz varsa ve AI Markdown dönerse COMPLETED işaretlenir (true).
 *  - Analiz varsa ama AI boş/null dönerse FAILED işaretlenir (false).
 */
class ReportPipelineServiceTest {

	private JdbcTemplate jdbcTemplate;
	private AiAnalysisService aiAnalysisService;
	private ReportService reportService;
	private ReportPipelineService service;

	private final UUID jobId = UUID.randomUUID();
	private final UUID reportId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		aiAnalysisService = org.mockito.Mockito.mock(AiAnalysisService.class);
		reportService = org.mockito.Mockito.mock(ReportService.class);
		// @RequiredArgsConstructor sırası: jdbcTemplate, aiAnalysisService, reportService
		service = new ReportPipelineService(jdbcTemplate, aiAnalysisService, reportService);
	}

	@SuppressWarnings("unchecked")
	@Test
	void analizYoksaRaporUretilmez() {
		// Analiz toplama sorgusu boş
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		boolean done = service.generateReport(jobId);

		// Boş tur -> AI ve rapor kaydı hiç dokunulmaz
		assertFalse(done);
		verify(reportService, never()).ensureReport(any(UUID.class));
		verify(aiAnalysisService, never()).generateReport(anyString());
	}

	@SuppressWarnings("unchecked")
	@Test
	void analizVarVeAiDonersaCompleted() {
		// Bir analiz satırı dönsün
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(sampleRow()));
		// Rapor kaydı garanti edilsin
		when(reportService.ensureReport(eq(jobId))).thenReturn(reportId);
		// AI geçerli Markdown döndürsün
		when(aiAnalysisService.generateReport(anyString())).thenReturn("# Rapor\nİçerik");

		boolean done = service.generateReport(jobId);

		// GENERATING -> COMPLETED akışı; true döner
		assertTrue(done);
		verify(reportService, times(1)).markGenerating(eq(reportId));
		verify(reportService, times(1)).markCompleted(eq(reportId), anyString());
		verify(reportService, never()).markFailed(any(UUID.class));
	}

	@SuppressWarnings("unchecked")
	@Test
	void aiBosDonerseFailed() {
		// Analiz var
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(sampleRow()));
		when(reportService.ensureReport(eq(jobId))).thenReturn(reportId);
		// AI null döndürsün (key yok / hata)
		when(aiAnalysisService.generateReport(anyString())).thenReturn(null);

		boolean done = service.generateReport(jobId);

		// FAILED işaretlenir; false döner
		assertFalse(done);
		verify(reportService, times(1)).markGenerating(eq(reportId));
		verify(reportService, times(1)).markFailed(eq(reportId));
		verify(reportService, never()).markCompleted(any(UUID.class), anyString());
	}

	// Test için örnek analiz satırı
	private ReportPostRow sampleRow() {
		return new ReportPostRow(
				"KENDİ HESABIN",
				"IMAGE",
				"örnek caption",
				"#a #b",
				100L, 10L, 0L,
				"{\"tone\":\"samimi\"}");
	}
}
