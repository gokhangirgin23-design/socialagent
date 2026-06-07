package com.api.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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

/**
 * ReportPipelineService için Spring'siz birim testi (DB/AI gerektirmez).
 *
 * Doğrulanan davranışlar (WorkerPrompt revizyonu — AccountReportRow aggregate yaklaşımı):
 *  - Analiz yoksa rapor üretilmez (AI ve ensureReport hiç çağrılmaz; false döner).
 *  - Analiz varsa ve AI Markdown dönerse COMPLETED işaretlenir (true döner).
 *  - Analiz varsa ama AI boş/null dönerse FAILED işaretlenir (false döner).
 *
 * Mock stratejisi:
 *   - loadAnalysisMode → SQL contains "analysis_mode"
 *   - loadOwnAndSectorPosts → SQL contains "source_type IN" (source_type IN (OWN,SECTOR))
 *   - loadMonitoredPosts   → SQL contains "monitored_account ma"  (join alias adı)
 * PostRaw, package-private olduğu için test paketinden doğrudan erişilebilir.
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
		service = new ReportPipelineService(jdbcTemplate, aiAnalysisService, reportService);
	}

	@SuppressWarnings("unchecked")
	@Test
	void analizYoksaRaporUretilmez() {
		// analysisMode sorgusu "NONE" döndürsün
		when(jdbcTemplate.query(contains("analysis_mode"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of("NONE"));
		// OWN+SECTOR sorgusu boş; source_type IN (OWN,SECTOR) koşulundan tanınır
		when(jdbcTemplate.query(contains("source_type IN"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());
		// MONITORED sorgusu boş; monitored_account ma alias'ından tanınır
		when(jdbcTemplate.query(contains("monitored_account ma"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		boolean done = service.generateReport(jobId);

		assertFalse(done);
		// Hiç rapor kaydı açılmamalı ve AI çağrılmamalı
		verify(reportService, never()).ensureReport(any(UUID.class));
		verify(aiAnalysisService, never()).generateReport(anyString());
	}

	@SuppressWarnings("unchecked")
	@Test
	void analizVarVeAiDonersaCompleted() {
		// analysisMode BOTH
		when(jdbcTemplate.query(contains("analysis_mode"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of("BOTH"));
		// OWN+SECTOR sorgusu bir KENDİ HESABIN satırı döndürsün (source_type IN)
		when(jdbcTemplate.query(contains("source_type IN"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(sampleOwnRow()));
		// MONITORED sorgusu boş (bu test için rakip yok)
		when(jdbcTemplate.query(contains("monitored_account ma"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());
		when(reportService.ensureReport(eq(jobId))).thenReturn(reportId);
		when(aiAnalysisService.generateReport(anyString())).thenReturn("# Rapor\nİçerik");

		boolean done = service.generateReport(jobId);

		assertTrue(done);
		verify(reportService, times(1)).markGenerating(eq(reportId));
		verify(reportService, times(1)).markCompleted(eq(reportId), anyString());
		verify(reportService, never()).markFailed(any(UUID.class));
	}

	@SuppressWarnings("unchecked")
	@Test
	void aiBosDonerseFailed() {
		when(jdbcTemplate.query(contains("analysis_mode"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of("OWN_ONLY"));
		when(jdbcTemplate.query(contains("source_type IN"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(sampleOwnRow()));
		when(jdbcTemplate.query(contains("monitored_account ma"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());
		when(reportService.ensureReport(eq(jobId))).thenReturn(reportId);
		when(aiAnalysisService.generateReport(anyString())).thenReturn(null); // AI başarısız

		boolean done = service.generateReport(jobId);

		assertFalse(done);
		verify(reportService, times(1)).markGenerating(eq(reportId));
		verify(reportService, times(1)).markFailed(eq(reportId));
		verify(reportService, never()).markCompleted(any(UUID.class), anyString());
	}

	@SuppressWarnings("unchecked")
	@Test
	void monitoredVerisiAggregateEdilir() {
		when(jdbcTemplate.query(contains("analysis_mode"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of("COMPETITOR_ONLY"));
		// OWN+SECTOR yok
		when(jdbcTemplate.query(contains("source_type IN"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());
		// Monitored iki farklı hesap: iki SECTOR + bir RAKİP satırı
		when(jdbcTemplate.query(contains("monitored_account ma"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(sampleMonitoredRow("rakip1"), sampleMonitoredRow("rakip2")));
		when(reportService.ensureReport(eq(jobId))).thenReturn(reportId);
		when(aiAnalysisService.generateReport(anyString())).thenReturn("# Sektör Raporu");

		boolean done = service.generateReport(jobId);

		assertTrue(done);
		verify(aiAnalysisService, times(1)).generateReport(anyString());
	}

	// PostRaw package-private olduğu için doğrudan erişilebilir (aynı paket)
	private ReportPipelineService.PostRaw sampleOwnRow() {
		return new ReportPipelineService.PostRaw(
				"KENDİ HESABIN",
				"kendi_hesap",
				"IMAGE",
				150L, 20L, 500L,
				"{\"metrics\":{\"contentType\":{\"isReel\":false}},\"visual\":{\"hasHuman\":true,\"hasModel\":false,\"isProductFocused\":true}}");
	}

	private ReportPipelineService.PostRaw sampleMonitoredRow(String accountName) {
		return new ReportPipelineService.PostRaw(
				"RAKİP",
				accountName,
				"VIDEO",
				200L, 30L, 1000L,
				"{\"metrics\":{\"contentType\":{\"isReel\":true}},\"visual\":{\"hasHuman\":false,\"hasModel\":true,\"isProductFocused\":false}}");
	}
}
