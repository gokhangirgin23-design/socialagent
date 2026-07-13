package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.ReportRequestDto;

/**
 * Gerçek JdbcTemplate + H2 üzerinden listRequests — BACKEND-TODO Sorun 3, madde 3.2'nin
 * regresyon testi. ensureReport() senkronizasyonsuz (SELECT sonra INSERT) olduğundan eşzamanlı
 * iki çağrı teorik olarak aynı request_id için birden fazla "report" satırı oluşturabilir; bu
 * test o durumu elle simüle edip listRequests'in düz LEFT JOIN yerine LATERAL JOIN ile HER ZAMAN
 * tek satır + en güncel report_id döndürdüğünü doğrular.
 *
 * @Transactional: her test sonunda ROLLBACK edilir, H2'ye kalıcı veri yazılmaz.
 */
@SpringBootTest
@Transactional
@DirtiesContext
class ReportRequestServiceListRequestsIT {

    @Autowired
    private ReportRequestService reportRequestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void ayniRequestIcinBirdenFazlaReportSatiriVarsaTekVeEnGuncelSatirDoner() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        jdbcTemplate.update("""
                INSERT INTO report_request
                    (request_id, user_id, report_type, queue_pushed, status, attempt_count, active,
                     created_date, updated_date, is_free_usage)
                VALUES (?, ?, 'NONE', 1, 'COMPLETED', 0, 1, ?, ?, 0)
                """, requestId, userId, now, now);

        // Race koşulunu simüle et: aynı request_id için iki ayrı report satırı, eskisi önce.
        UUID oldReportId = UUID.randomUUID();
        UUID newReportId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO report (report_id, request_id, status, created_date, updated_date)
                VALUES (?, ?, 'COMPLETED', ?, ?)
                """, oldReportId, requestId, now.minusMinutes(5), now.minusMinutes(5));
        jdbcTemplate.update("""
                INSERT INTO report (report_id, request_id, status, created_date, updated_date)
                VALUES (?, ?, 'COMPLETED', ?, ?)
                """, newReportId, requestId, now, now);

        List<ReportRequestDto> result = reportRequestService.listRequests(userId, 0, 10);

        assertEquals(1, result.size(), "Aynı request_id için tek satır dönmeli, satır çoğalmamalı");
        assertEquals(newReportId, result.get(0).getReportId(), "En güncel (created_date DESC) report_id dönmeli");
    }
}
