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
 * Doğrulanan davranışlar:
 *  - findReportIdByRequest: kayıt varsa id, yoksa null döner.
 *  - createPending: JPA saveAndFlush çağrılır, PENDING id döner.
 *  - ensureReport: mevcut rapor varsa onu döndürür (saveAndFlush YOK), yoksa oluşturur (saveAndFlush VAR).
 *  - markCompleted: native UPDATE çağrılır (içerik dahil).
 */
class ReportServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ReportRepository reportRepository;
    private ReportService service;

    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        reportRepository = org.mockito.Mockito.mock(ReportRepository.class);
        service = new ReportService(jdbcTemplate, reportRepository);
    }

    @SuppressWarnings("unchecked")
    @Test
    void findReportIdRequestYoksaNull() {
        // Lookup boş -> rapor yok; tek UUID vararg için any(UUID.class) kullanılır
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(UUID.class)))
                .thenReturn(List.of());

        assertNull(service.findReportIdByRequest(requestId));
    }

    @Test
    void createPendingSaveAndFlushCagrilir() {
        // createPending saveAndFlush kullanır (JPA flush garantisi)
        UUID id = service.createPending(requestId);

        verify(reportRepository, times(1)).saveAndFlush(any(Report.class));
        assertNotNull(id);
    }

    @SuppressWarnings("unchecked")
    @Test
    void ensureReportMevcutsaYenisiOlusturulmaz() {
        // Lookup mevcut bir rapor id'si döndürsün
        UUID existing = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(UUID.class)))
                .thenReturn(List.of(existing));

        UUID result = service.ensureReport(requestId);

        // Mevcut id döner; insert yapılmaz
        assertEquals(existing, result);
        verify(reportRepository, never()).saveAndFlush(any(Report.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void ensureReportYoksaOlusturulur() {
        // Lookup boş -> yeni oluşturulmalı
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(UUID.class)))
                .thenReturn(List.of());

        UUID result = service.ensureReport(requestId);

        // Yeni id döner; saveAndFlush çağrılır
        assertNotNull(result);
        verify(reportRepository, times(1)).saveAndFlush(any(Report.class));
    }

    @Test
    void markCompletedNativeUpdateCagrilir() {
        UUID reportId = UUID.randomUUID();

        service.markCompleted(reportId, "# Rapor\nMarkdown içerik");

        // update(sql, status, content, timestamp, reportId): 4 vararg elementi
        verify(jdbcTemplate, times(1)).update(anyString(), eq("COMPLETED"), anyString(), any(), any(UUID.class));
    }

    @Test
    void markFailedNativeUpdateCagrilir() {
        UUID reportId = UUID.randomUUID();

        service.markFailed(reportId);

        verify(jdbcTemplate, times(1)).update(anyString(), eq("FAILED"), any(), eq(reportId));
    }
}
