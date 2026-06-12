package com.api.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.dto.AnalysisSelectabilityDto;
import com.api.dto.CreateReportRequestDto;
import com.api.dto.ReportRequestDto;
import com.api.dto.repository.ReportRequestRepository;
import com.api.entity.AnalysisMode;
import com.api.entity.ReportRequest;
import com.api.mapper.ReportRequestMapper;
import com.api.messaging.JobQueueProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rapor isteği oluşturma, listeleme ve seçilebilirlik iş mantığı.
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Temel akış:
 *   1) reportType kullanıcı tarafından açıkça seçilir (otomatik belirlenmez).
 *   2) İstek tabloya eklenir, ardından direkt kuyruğa basılır (scheduler yok).
 *   3) Kuyruk FIFO mantığıyla çalışır; worker pipeline'ı tetikler.
 *
 * Doğrulama:
 *   - OWN_ONLY / BOTH seçilmişse kullanıcının aktif kendi hesabı olmalı.
 *   - COMPETITOR_ONLY / BOTH seçilmişse en az bir izlenen hesap olmalı.
 *   - NONE / OWN_ONLY seçilmişse sektör seçili olmalı (hashtag araştırması için).
 *
 * Lookup'lar JdbcTemplate native + text-block SQL + "?" (CLAUDE.md Madde 6).
 * Insert JPA save, güncelleme (queue_pushed flag) native UPDATE.
 * userId daima JWT'den (CLAUDE.md Madde 4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportRequestService {

    // Native sorgular için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // report_request insert için JPA repository
    private final ReportRequestRepository reportRequestRepository;

    // ReportRequest entity -> DTO dönüştürücü
    private final ReportRequestMapper reportRequestMapper;

    // Kuyruğa basan producer
    private final JobQueueProducer jobQueueProducer;

    /**
     * Yeni rapor isteği oluşturur ve kuyruğa basar.
     * reportType istekten gelir; hesap doluluk durumuna göre OTOMATİK BELİRLENMEZ.
     * Endpoint: POST /report-request/create
     */
    @Transactional
    public ReportRequestDto createRequest(UUID userId, CreateReportRequestDto req) {
        LocalDateTime now = LocalDateTime.now();

        // 1) reportType geçerli bir enum değeri mi?
        AnalysisMode mode = parseAnalysisMode(req.getReportType());

        // 2) Kendi hesap kontrolü (OWN_ONLY / BOTH modunda zorunlu)
        UUID ownAccountId = null;
        if (mode == AnalysisMode.OWN_ONLY || mode == AnalysisMode.BOTH) {
            ownAccountId = findOwnAccountId(userId);
            if (ownAccountId == null) {
                throw new ApiException(ResponseCode.VALIDATION_ERROR,
                        mode.name() + " modu için aktif kendi hesabınız bulunmamaktadır. Önce hesap ekleyin.");
            }
        }

        // 3) Rakip hesap kontrolü (COMPETITOR_ONLY / BOTH modunda zorunlu)
        if (mode == AnalysisMode.COMPETITOR_ONLY || mode == AnalysisMode.BOTH) {
            if (!hasMonitoredAccounts(userId)) {
                throw new ApiException(ResponseCode.VALIDATION_ERROR,
                        mode.name() + " modu için izlenen rakip hesap bulunmamaktadır. Önce rakip hesap ekleyin.");
            }
        }

        // 4) Sektör zorunluluğu: NONE ve OWN_ONLY modlarında hashtag araştırması için gerekli
        if (mode == AnalysisMode.NONE || mode == AnalysisMode.OWN_ONLY) {
            if (!hasSectorSelected(userId)) {
                throw new ApiException(ResponseCode.VALIDATION_ERROR,
                        "Sektör araştırması için önce sektör seçilmelidir.");
            }
        }

        // 5) report_request kaydını oluştur (queue_pushed=0, aktif)
        ReportRequest request = new ReportRequest();
        request.setRequestId(UUID.randomUUID());
        request.setUserId(userId);
        request.setReportType(mode.name());
        request.setSelectedUserSocialAccountId(ownAccountId);
        request.setQueuePushed(0);
        request.setActive(1);
        request.setCreatedDate(now);
        request.setUpdatedDate(now);

        // JPA save ile insert
        ReportRequest saved = reportRequestRepository.save(request);

        // 6) Kuyruğa bas; hata olursa queue_error alanına yaz (istek yine kaydedildi)
        try {
            jobQueueProducer.publishRequest(saved.getRequestId());
            // Başarılı: queue_pushed=1 ve push zamanını güncelle
            jdbcTemplate.update(
                    "UPDATE report_request SET queue_pushed = 1, queue_push_date = ?, updated_date = ? WHERE request_id = ?",
                    Timestamp.valueOf(now), Timestamp.valueOf(now), saved.getRequestId());
            saved.setQueuePushed(1);
            saved.setQueuePushDate(now);
            log.info("Rapor isteği oluşturuldu ve kuyruğa basıldı: requestId={}, userId={}, tip={}",
                    saved.getRequestId(), userId, mode);
        } catch (Exception ex) {
            // Kuyruk hatası: hata mesajını kaydet; istek geçerli, worker ileride tetiklenebilir
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            jdbcTemplate.update(
                    "UPDATE report_request SET queue_error = ?, updated_date = ? WHERE request_id = ?",
                    errorMsg, Timestamp.valueOf(now), saved.getRequestId());
            saved.setQueueError(errorMsg);
            log.warn("Rapor isteği kaydedildi ancak kuyruğa basılamadı: requestId={}, hata={}",
                    saved.getRequestId(), errorMsg);
        }

        return reportRequestMapper.toDto(saved);
    }

    /**
     * Kullanıcının rapor isteklerini sayfalı listeler (en yeni önce).
     * Endpoint: POST /report-request/list
     */
    @Transactional(readOnly = true)
    public List<ReportRequestDto> listRequests(UUID userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = (size > 0) ? size : 10;
        int offset = safePage * safeSize;

        String sql = """
                SELECT request_id, user_id, report_type, selected_user_social_account_id,
                       queue_pushed, queue_push_date, queue_error, active, created_date, updated_date
                FROM report_request
                WHERE user_id = ? AND active = 1
                ORDER BY created_date DESC
                LIMIT ? OFFSET ?
                """;
        List<ReportRequest> requests = jdbcTemplate.query(sql, REQUEST_ROW_MAPPER, userId, safeSize, offset);
        return reportRequestMapper.toDtoList(requests);
    }

    /**
     * Kullanıcının hangi analiz türlerini seçebileceğini döndürür (frontend için).
     * OWN_SELECTABLE    : aktif kendi hesabı varsa true
     * COMPETITOR_SELECTABLE : izlenen hesap varsa true
     * BOTH_SELECTABLE   : her ikisi birden varsa true
     * NONE_SELECTABLE   : her zaman true
     * Endpoint: POST /report-request/available-types
     */
    @Transactional(readOnly = true)
    public AnalysisSelectabilityDto getAnalysisSelectability(UUID userId) {
        boolean hasOwn = findOwnAccountId(userId) != null;
        boolean hasMonitored = hasMonitoredAccounts(userId);
        return new AnalysisSelectabilityDto(hasOwn, hasMonitored, hasOwn && hasMonitored);
    }

    // ============================================================
    // Yardımcı metodlar
    // ============================================================

    /**
     * Kullanıcının aktif kendi sosyal hesabının id'sini döndürür; yoksa null.
     */
    private UUID findOwnAccountId(UUID userId) {
        String sql = """
                SELECT user_social_account_id
                FROM user_social_account
                WHERE user_id = ? AND active = 1
                LIMIT 1
                """;
        List<UUID> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getObject("user_social_account_id", UUID.class),
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Kullanıcının en az bir aktif izlenen rakip hesabı var mı?
     */
    private boolean hasMonitoredAccounts(UUID userId) {
        String sql = """
                SELECT user_monitored_account_id
                FROM user_monitored_account
                WHERE user_id = ? AND active = 1
                LIMIT 1
                """;
        List<UUID> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getObject("user_monitored_account_id", UUID.class),
                userId);
        return !rows.isEmpty();
    }

    /**
     * Kullanıcı sektör seçmiş mi? (NONE / OWN_ONLY için ön koşul).
     */
    private boolean hasSectorSelected(UUID userId) {
        String sql = """
                SELECT sector_id
                FROM user_info
                WHERE user_id = ? AND active = 1
                """;
        List<UUID> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getObject("sector_id", UUID.class),
                userId);
        return !rows.isEmpty() && rows.get(0) != null;
    }

    /**
     * String değeri AnalysisMode enum'una çevirir; geçersiz değerde VALIDATION_ERROR fırlatır.
     */
    private AnalysisMode parseAnalysisMode(String value) {
        try {
            return AnalysisMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "Geçersiz reportType değeri: " + value + ". Geçerli değerler: OWN_ONLY, COMPETITOR_ONLY, BOTH, NONE");
        }
    }

    // report_request satırını entity'ye çeviren RowMapper (liste sorguları için)
    private static final RowMapper<ReportRequest> REQUEST_ROW_MAPPER = (rs, rowNum) -> {
        ReportRequest r = new ReportRequest();
        r.setRequestId(rs.getObject("request_id", UUID.class));
        r.setUserId(rs.getObject("user_id", UUID.class));
        r.setReportType(rs.getString("report_type"));
        r.setSelectedUserSocialAccountId(rs.getObject("selected_user_social_account_id", UUID.class));
        r.setQueuePushed(rs.getObject("queue_pushed", Integer.class));
        if (rs.getTimestamp("queue_push_date") != null) {
            r.setQueuePushDate(rs.getTimestamp("queue_push_date").toLocalDateTime());
        }
        r.setQueueError(rs.getString("queue_error"));
        r.setActive(rs.getObject("active", Integer.class));
        if (rs.getTimestamp("created_date") != null) {
            r.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
        }
        if (rs.getTimestamp("updated_date") != null) {
            r.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
        }
        return r;
    };
}
