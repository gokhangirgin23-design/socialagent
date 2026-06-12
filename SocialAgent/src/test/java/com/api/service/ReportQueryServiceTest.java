package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.api.dto.ReportDto;
import com.api.dto.ReportSummaryDto;
import com.api.entity.Report;
import com.api.mapper.ReportMapper;

/**
 * ReportQueryService için Spring'siz birim testi (DB gerektirmez).
 * JdbcTemplate + ReportMapper mock'lanır.
 * Doğrulanan davranışlar:
 *  - listReports: native join sonuçlarını döner (3 vararg: userId, size, offset).
 *  - getReportDetail: rapor sahibe aitse DTO; değilse/yoksa null.
 */
class ReportQueryServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ReportMapper reportMapper;
    private ReportQueryService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        reportMapper = org.mockito.Mockito.mock(ReportMapper.class);
        service = new ReportQueryService(jdbcTemplate, reportMapper);
    }

    @SuppressWarnings("unchecked")
    @Test
    void listReportsOzetListeDoner() {
        // listReports -> query(sql, mapper, userId, size, offset): 3 vararg elementi
        ReportSummaryDto row = new ReportSummaryDto();
        row.setReportId(reportId);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of(row));

        List<ReportSummaryDto> result = service.listReports(userId, 0, 10);

        assertEquals(1, result.size());
        assertEquals(reportId, result.get(0).getReportId());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getReportDetailSahibeAitseDtoDoner() {
        // getReportDetail -> query(sql, mapper, reportId, userId): 2 UUID vararg
        Report report = new Report();
        report.setReportId(reportId);
        report.setReportContent("# Rapor\nİçerik");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(UUID.class), any(UUID.class)))
                .thenReturn(List.of(report));
        // MapStruct dönüşümü
        ReportDto dto = new ReportDto();
        dto.setReportId(reportId);
        when(reportMapper.toDto(any(Report.class))).thenReturn(dto);

        ReportDto result = service.getReportDetail(userId, reportId);

        assertNotNull(result);
        assertEquals(reportId, result.getReportId());
        verify(reportMapper, times(1)).toDto(any(Report.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getReportDetailSahibeAitDegilseNullDoner() {
        // Sahiplik join'i boş -> rapor yok ya da başka kullanıcının
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(UUID.class), any(UUID.class)))
                .thenReturn(List.of());

        ReportDto result = service.getReportDetail(userId, reportId);

        assertNull(result);
        // Dönüşüm hiç çağrılmamalı
        verify(reportMapper, never()).toDto(any(Report.class));
    }
}
