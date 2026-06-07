package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.api.dto.repository.ReportRepository;
import com.api.entity.Report;

/**
 * ReportService için Spring'siz birim testi (DB gerektirmez).
 * JdbcTemplate ve ReportRepository mock'lanır.
 * Doğrulanan davranışlar (FAZ 7):
 *  - findReportIdByJob: kayıt varsa id, yoksa null döner.
 *  - createPending: JPA save çağrılır, PENDING id döner.
 *  - ensureReport: mevcut rapor varsa onu döndürür (save YOK), yoksa oluşturur (save VAR).
 *  - markCompleted: native UPDATE çağrılır (içerik dahil).
 */
class ReportServiceTest {

	private JdbcTemplate jdbcTemplate;
	private ReportRepository reportRepository;
	private ReportService service;

	private final UUID jobId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		reportRepository = org.mockito.Mockito.mock(ReportRepository.class);
		// @RequiredArgsConstructor sırası: jdbcTemplate, reportRepository
		service = new ReportService(jdbcTemplate, reportRepository);
	}

	@SuppressWarnings("unchecked")
	@Test
	void findReportIdJobYoksaNull() {
		// Lookup boş -> rapor yok
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		assertNull(service.findReportIdByJob(jobId));
	}

	@SuppressWarnings("unchecked")
	@Test
	void createPendingSaveCagrilir() {
		UUID id = service.createPending(jobId);

		// Yeni rapor -> save çağrılır ve geçerli id döner
		verify(reportRepository, times(1)).save(any(Report.class));
		assertNotNull(id);
	}

	@SuppressWarnings("unchecked")
	@Test
	void ensureReportMevcutsaYenisiOlusturulmaz() {
		// Lookup mevcut bir rapor id'si döndürsün
		UUID existing = UUID.randomUUID();
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(existing));

		UUID result = service.ensureReport(jobId);

		// Mevcut id döner; insert yapılmaz
		assertEquals(existing, result);
		verify(reportRepository, never()).save(any(Report.class));
	}

	@SuppressWarnings("unchecked")
	@Test
	void ensureReportYoksaOlusturulur() {
		// Lookup boş -> yeni oluşturulmalı
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		UUID result = service.ensureReport(jobId);

		// Yeni id döner; insert yapılır
		assertNotNull(result);
		verify(reportRepository, times(1)).save(any(Report.class));
	}

	@Test
	void markCompletedNativeUpdateCagrilir() {
		UUID reportId = UUID.randomUUID();

		service.markCompleted(reportId, "# Rapor\nMarkdown içerik");

		// İçerikli durum güncellemesi -> native UPDATE en az 1 kez
		verify(jdbcTemplate, times(1)).update(anyString(), (Object[]) any());
	}

	@Test
	void markFailedNativeUpdateCagrilir() {
		UUID reportId = UUID.randomUUID();

		service.markFailed(reportId);

		verify(jdbcTemplate, times(1)).update(anyString(), eq("FAILED"), any(), eq(reportId));
	}
}
