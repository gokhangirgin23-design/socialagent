package com.api.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.repository.ReportRepository;
import com.api.entity.Report;
import com.api.entity.ReportStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * report yazma + durum akışı yönetimi (CLAUDE.md Bölüm 11). Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Her rapor isteği için TEK rapor tutulur: kayıt yoksa insert (JPA save), varsa aynı kayıt yenilenir.
 * Durum akışı: PENDING -> GENERATING -> COMPLETED | FAILED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    // Native lookup + durum güncellemeleri için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // report insert için JPA repository
    private final ReportRepository reportRepository;

    /**
     * Bu rapor isteği için var olan rapor kaydının id'sini döndürür (yoksa null).
     */
    @Transactional(readOnly = true)
    public UUID findReportIdByRequest(UUID requestId) {
        String sql = """
                SELECT report_id
                FROM report
                WHERE request_id = ?
                ORDER BY created_date DESC
                """;
        List<UUID> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getObject("report_id", UUID.class),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Rapor isteği için yeni bir rapor kaydını PENDING durumunda oluşturur (JPA save).
     *
     * @return oluşturulan report_id
     */
    @Transactional
    public UUID createPending(UUID requestId) {
        LocalDateTime now = LocalDateTime.now();
        Report report = new Report();
        report.setReportId(UUID.randomUUID());
        report.setRequestId(requestId);
        report.setStatus(ReportStatus.PENDING.name());
        report.setReportContent(null);
        report.setCreatedDate(now);
        report.setUpdatedDate(now);

        reportRepository.saveAndFlush(report);
        log.info("report oluşturuldu (PENDING): requestId={}, reportId={}", requestId, report.getReportId());
        return report.getReportId();
    }

    /**
     * Rapor isteği için rapor kaydını garanti eder: varsa id'sini döndürür, yoksa PENDING oluşturur.
     */
    @Transactional
    public UUID ensureReport(UUID requestId) {
        UUID existing = findReportIdByRequest(requestId);
        return (existing != null) ? existing : createPending(requestId);
    }

    /**
     * Raporu GENERATING durumuna geçirir (üretim başladı).
     */
    @Transactional
    public void markGenerating(UUID reportId) {
        updateStatus(reportId, ReportStatus.GENERATING, null);
        log.debug("report -> GENERATING: reportId={}", reportId);
    }

    /**
     * Raporu COMPLETED durumuna geçirir ve Markdown içeriği yazar.
     */
    @Transactional
    public void markCompleted(UUID reportId, String markdown) {
        updateStatus(reportId, ReportStatus.COMPLETED, markdown);
        log.info("report -> COMPLETED: reportId={}", reportId);
    }

    /**
     * Raporu FAILED durumuna geçirir (AI yok / hata / boş çıktı). İçerik korunur (null geçilir).
     */
    @Transactional
    public void markFailed(UUID reportId) {
        updateStatus(reportId, ReportStatus.FAILED, null);
        log.warn("report -> FAILED: reportId={}", reportId);
    }

    /**
     * Durum + (opsiyonel) içerik güncellemesini native UPDATE ile uygular. content null ise yalnızca durum güncellenir.
     */
    private void updateStatus(UUID reportId, ReportStatus status, String content) {
        LocalDateTime now = LocalDateTime.now();
        if (content == null) {
            String sql = """
                    UPDATE report
                    SET status = ?, updated_date = ?
                    WHERE report_id = ?
                    """;
            jdbcTemplate.update(sql, status.name(), Timestamp.valueOf(now), reportId);
        } else {
            String sql = """
                    UPDATE report
                    SET status = ?, report_content = ?, updated_date = ?
                    WHERE report_id = ?
                    """;
            jdbcTemplate.update(sql, status.name(), content, Timestamp.valueOf(now), reportId);
        }
    }
}
