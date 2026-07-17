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
import org.mockito.ArgumentCaptor;
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
 * Mock stratejisi (Geliştirme — rakip hesap özelliğinin kaldırılması sonrası: yalnızca OWN/SECTOR
 * kaynakları var, loadMonitoredPosts kaldırıldığından ilgili stub da kaldırıldı):
 *   - loadReportType       → SQL contains "report_type"
 *   - loadOwnAndSectorPosts → SQL contains "source_type IN" (source_type IN (OWN,SECTOR))
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
		// loadReportType sorgusu "NONE" döndürsün
		when(jdbcTemplate.query(contains("report_type"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of("NONE"));
		// OWN+SECTOR sorgusu boş; source_type IN (OWN,SECTOR) koşulundan tanınır
		when(jdbcTemplate.query(contains("source_type IN"), any(RowMapper.class), (Object[]) any()))
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
		// loadReportType OWN_ONLY (karşılaştırmalı prompt)
		when(jdbcTemplate.query(contains("report_type"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of("OWN_ONLY"));
		// OWN+SECTOR sorgusu bir KENDİ HESABIN satırı döndürsün (source_type IN)
		when(jdbcTemplate.query(contains("source_type IN"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(sampleOwnRow()));
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
		when(jdbcTemplate.query(contains("report_type"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of("OWN_ONLY"));
		when(jdbcTemplate.query(contains("source_type IN"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(sampleOwnRow()));
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
	void alakasizSektorHesabiRapordanDislanir() {
		// Moda/Lüks Moda vakası: Apify "Lüks Moda" aramasında 2 gerçek moda hesabı + 1 alakasız
		// emlak hesabı buluyor (kullanıcı adında "moda"/"luks" geçtiği için yanlış eşleşme).
		when(jdbcTemplate.query(contains("report_type"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of("NONE"));
		when(jdbcTemplate.query(contains("source_type IN"), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(
						sampleSectorRow("moda_hesap_1", "kadın giyim"),
						sampleSectorRow("moda_hesap_2", "erkek giyim"),
						sampleSectorRow("emlak_hesap", "gayrimenkul")));
		when(reportService.ensureReport(eq(jobId))).thenReturn(reportId);
		ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
		when(aiAnalysisService.generateReport(promptCaptor.capture())).thenReturn("# Rapor");

		boolean done = service.generateReport(jobId);

		assertTrue(done);
		String prompt = promptCaptor.getValue();
		assertTrue(prompt.contains("@moda_hesap_1"), "İlgili sektör hesabı prompt'ta kalmalı: " + prompt);
		assertTrue(prompt.contains("@moda_hesap_2"), "İlgili sektör hesabı prompt'ta kalmalı: " + prompt);
		assertFalse(prompt.contains("@emlak_hesap"), "Alakasız hesap prompt'tan dışlanmalı: " + prompt);
	}

	private ReportPipelineService.PostRaw sampleSectorRow(String accountName, String productCategory) {
		return new ReportPipelineService.PostRaw(
				"SEKTÖR",
				accountName,
				"IMAGE",
				50L, 5L, 500L,
				"{\"visual\":{\"productCategory\":\"" + productCategory + "\"}}");
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
}
