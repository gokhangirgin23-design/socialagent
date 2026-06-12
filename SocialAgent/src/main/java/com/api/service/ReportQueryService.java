package com.api.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.ReportDto;
import com.api.dto.ReportSummaryDto;
import com.api.entity.Report;
import com.api.mapper.ReportMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dashboard rapor sorguları (CLAUDE.md Bölüm 12).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Rapor YAZIMI/durum akışı ReportService'tedir; bu servis yalnızca OKUMA (dashboard):
 *   - listReports: kullanıcı bazlı sayfalı rapor listesi (içeriksiz özet).
 *   - getReportDetail: tek raporun Markdown içeriği (yalnızca sahibi — ownership join).
 *
 * Tüm sorgular JdbcTemplate native + eski stil "=" join (CLAUDE.md Madde 6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportQueryService {

    // Native join sorguları için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // Report entity -> ReportDto (detay) dönüştürücü
    private final ReportMapper reportMapper;

    /**
     * Kullanıcının raporlarını sayfalı listeler (en yeni önce). İçerik (Markdown) TAŞINMAZ.
     * report ⋈ report_request (eski stil "="), rr.user_id = ? ile kullanıcıya filtrelenir.
     * Endpoint: POST /report/list
     */
    @Transactional(readOnly = true)
    public List<ReportSummaryDto> listReports(UUID userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = (size > 0) ? size : 10;
        int offset = safePage * safeSize;

        // report ⋈ report_request — kullanıcının raporları (içerik kolonu seçilmez: liste hafif)
        String sql = """
                SELECT r.report_id, r.request_id, r.status,
                       rr.report_type,
                       r.created_date, r.updated_date
                FROM report r, report_request rr
                WHERE r.request_id = rr.request_id
                  AND rr.user_id = ?
                ORDER BY r.created_date DESC
                LIMIT ? OFFSET ?
                """;
        return jdbcTemplate.query(sql, SUMMARY_ROW_MAPPER, userId, safeSize, offset);
    }

    /**
     * Tek raporun detayını (Markdown içerik dahil) döner — yalnızca raporun sahibine.
     * Sahiplik, report ⋈ report_request join'inde rr.user_id = ? ile sağlanır.
     * Endpoint: POST /report/detail
     *
     * @return rapor DTO'su ya da (yok/erişim yok) null
     */
    @Transactional(readOnly = true)
    public ReportDto getReportDetail(UUID userId, UUID reportId) {
        // Sahiplik korumalı detay sorgusu (report ⋈ report_request, eski stil "=")
        String sql = """
                SELECT r.report_id, r.request_id, r.status, r.report_content,
                       r.created_date, r.updated_date
                FROM report r, report_request rr
                WHERE r.report_id = ?
                  AND r.request_id = rr.request_id
                  AND rr.user_id = ?
                """;
        List<Report> rows = jdbcTemplate.query(sql, REPORT_ROW_MAPPER, reportId, userId);
        if (rows.isEmpty()) {
            log.info("Rapor bulunamadı veya erişim yok: userId={}, reportId={}", userId, reportId);
            return null;
        }
        return reportMapper.toDto(rows.get(0));
    }

    // ============================================================
    // RowMapper'lar
    // ============================================================

    // report ⋈ report_request özet satırı -> ReportSummaryDto (içeriksiz)
    private static final RowMapper<ReportSummaryDto> SUMMARY_ROW_MAPPER = (rs, rowNum) -> {
        ReportSummaryDto dto = new ReportSummaryDto();
        dto.setReportId(rs.getObject("report_id", UUID.class));
        dto.setRequestId(rs.getObject("request_id", UUID.class));
        dto.setStatus(rs.getString("status"));
        dto.setReportType(rs.getString("report_type"));
        if (rs.getTimestamp("created_date") != null) {
            dto.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
        }
        if (rs.getTimestamp("updated_date") != null) {
            dto.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
        }
        return dto;
    };

    // report detay satırı -> Report entity (Markdown içerik dahil; sonra MapStruct ile DTO'ya)
    private static final RowMapper<Report> REPORT_ROW_MAPPER = (rs, rowNum) -> {
        Report r = new Report();
        r.setReportId(rs.getObject("report_id", UUID.class));
        r.setRequestId(rs.getObject("request_id", UUID.class));
        r.setStatus(rs.getString("status"));
        r.setReportContent(rs.getString("report_content"));
        if (rs.getTimestamp("created_date") != null) {
            r.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
        }
        if (rs.getTimestamp("updated_date") != null) {
            r.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
        }
        return r;
    };
}
