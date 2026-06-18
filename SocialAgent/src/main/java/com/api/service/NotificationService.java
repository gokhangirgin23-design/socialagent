package com.api.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.NotificationDto;
import com.api.dto.repository.NotificationRepository;
import com.api.entity.Notification;
import com.api.entity.NotificationChannel;
import com.api.entity.ReferenceType;
import com.api.mapper.NotificationMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bildirim iş mantığı (CLAUDE.md Bölüm 12).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Sorumluluklar:
 *   1) notifyReportCompleted: rapor COMPLETED olunca DB'ye notification yaz + mail + push.
 *   2) listNotifications: kullanıcının bildirimlerini sayfalı listele (dashboard).
 *   3) markAsRead: bir bildirimi okundu işaretle (yalnızca sahibi).
 *   4) unreadCount: okunmamış bildirim sayısı (dashboard rozeti).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    // Native lookup/join/update/count için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // notification insert için JPA repository
    private final NotificationRepository notificationRepository;

    // Notification entity -> DTO dönüştürücü
    private final NotificationMapper notificationMapper;

    // E-posta gönderim adaptörü
    private final MailSender mailSender;

    // Push gönderim adaptörü
    private final PushSender pushSender;

    /**
     * Bir rapor isteğinin raporu COMPLETED olduğunda kullanıcıya bildirim üretir.
     * Bağımsız transaction (REQUIRES_NEW): bildirim hatası pipeline'ı kirletmez.
     *
     * @param requestId raporu tamamlanan rapor isteği
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyReportCompleted(UUID requestId) {
        // Tamamlanmış raporu, sahibini ve e-postasını native join ile çek.
        // report ⋈ report_request ⋈ user_info — eski stil "=" (CLAUDE.md Madde 6).
        ReportTarget target = loadCompletedReportTarget(requestId);
        if (target == null) {
            log.info("Bildirim için tamamlanmış rapor bulunamadı: requestId={}", requestId);
            return;
        }

        // Ortak bildirim metni
        LocalDateTime now = LocalDateTime.now();
        String title = "Raporunuz hazır";
        String modeText = (target.reportType() != null) ? target.reportType() : "Analiz";
        String message = modeText + " raporunuz oluşturuldu. Detay için panele göz atın.";

        // Her kanalı ayrı ayrı gönder; sonucu ayrı notification satırına yaz.
        SendResult mailRes = mailSender.send(target.email(), title, message);
        saveChannelNotification(target, title, message, NotificationChannel.MAIL, mailRes, now);

        SendResult pushRes = pushSender.send(target.userId(), title, message);
        saveChannelNotification(target, title, message, NotificationChannel.PUSH_NOTIFICATION, pushRes, now);
    }

    /**
     * Tek bir kanal için notification satırı yazar (JPA save).
     */
    private void saveChannelNotification(ReportTarget target, String title, String message,
            NotificationChannel channel, SendResult result, LocalDateTime now) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID());
        n.setUserId(target.userId());
        n.setTitle(title);
        n.setMessage(message);
        n.setReferenceType(ReferenceType.REPORT.name());
        n.setReferenceId(target.reportId());
        n.setChannel(channel.name());
        n.setSuccess(result.success() ? 1 : 0);
        n.setErrorDetail(result.errorDetail());
        n.setIsRead(0);
        n.setCreatedDate(now);
        n.setUpdatedDate(now);
        notificationRepository.save(n);
        log.info("Bildirim oluşturuldu: userId={}, reportId={}, channel={}, success={}, notificationId={}",
                target.userId(), target.reportId(), channel, result.success(), n.getNotificationId());
    }

    /**
     * Kullanıcının bildirimlerini sayfalı listeler (en yeni önce). Dashboard'da kullanılır.
     * Endpoint: POST /notification/list
     */
    @Transactional(readOnly = true)
    public List<NotificationDto> listNotifications(UUID userId, int page, int size, boolean onlyUnread) {
        int safePage = Math.max(page, 0);
        int safeSize = (size > 0) ? size : 10;
        int offset = safePage * safeSize;

        // Temel sorgu (kullanıcı bazlı); dashboard yalnız PUSH_NOTIFICATION satırlarını gösterir.
        String baseSql = """
                SELECT notification_id, user_id, title, message,
                       reference_type, reference_id, channel, success, error_detail,
                       is_read, created_date, updated_date
                FROM notification
                WHERE user_id = ?
                  AND channel = 'PUSH_NOTIFICATION'
                """;
        String filterSql = onlyUnread ? "AND is_read = 0\n" : "";
        String orderSql = """
                ORDER BY created_date DESC
                LIMIT ? OFFSET ?
                """;
        String sql = baseSql + filterSql + orderSql;
        List<Notification> rows = jdbcTemplate.query(sql, NOTIFICATION_ROW_MAPPER, userId, safeSize, offset);
        return notificationMapper.toDtoList(rows);
    }

    /**
     * Bir bildirimi okundu (is_read=1) işaretler. Yalnızca bildirimin sahibi işaretleyebilir.
     * Endpoint: POST /notification/read
     *
     * @return işaretlenen satır sayısı (0 ise kullanıcının böyle bir bildirimi yok)
     */
    @Transactional
    public int markAsRead(UUID userId, UUID notificationId) {
        LocalDateTime now = LocalDateTime.now();
        String sql = """
                UPDATE notification
                SET is_read = 1, updated_date = ?
                WHERE notification_id = ? AND user_id = ?
                """;
        int updated = jdbcTemplate.update(sql, Timestamp.valueOf(now), notificationId, userId);
        log.debug("Bildirim okundu işaretlendi: userId={}, notificationId={}, etkilenen={}",
                userId, notificationId, updated);
        return updated;
    }

    /**
     * Kullanıcının okunmamış bildirim sayısını döner (dashboard rozeti).
     * Endpoint: POST /notification/unread-count
     */
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        String sql = """
                SELECT COUNT(*)
                FROM notification
                WHERE user_id = ? AND is_read = 0
                  AND channel = 'PUSH_NOTIFICATION'
                """;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, userId);
        return (count != null) ? count : 0L;
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    /**
     * Rapor isteği için tamamlanmış raporu, sahibini ve e-postasını native join ile çeker.
     * report ⋈ report_request ⋈ user_info — eski stil "=" inner join (CLAUDE.md Madde 6).
     */
    private ReportTarget loadCompletedReportTarget(UUID requestId) {
        String sql = """
                SELECT r.report_id, rr.user_id, rr.report_type, u.email
                FROM report r, report_request rr, user_info u
                WHERE r.request_id = rr.request_id
                  AND rr.user_id = u.user_id
                  AND r.request_id = ?
                  AND r.status = 'COMPLETED'
                ORDER BY r.created_date DESC
                """;
        List<ReportTarget> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new ReportTarget(
                rs.getObject("report_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("report_type"),
                rs.getString("email")),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // notification satırını entity'ye çeviren RowMapper
    private static final org.springframework.jdbc.core.RowMapper<Notification> NOTIFICATION_ROW_MAPPER =
            (rs, rowNum) -> {
                Notification n = new Notification();
                n.setNotificationId(rs.getObject("notification_id", UUID.class));
                n.setUserId(rs.getObject("user_id", UUID.class));
                n.setTitle(rs.getString("title"));
                n.setMessage(rs.getString("message"));
                n.setReferenceType(rs.getString("reference_type"));
                n.setReferenceId(rs.getObject("reference_id", UUID.class));
                n.setChannel(rs.getString("channel"));
                n.setSuccess(rs.getObject("success", Integer.class));
                n.setErrorDetail(rs.getString("error_detail"));
                n.setIsRead(rs.getObject("is_read", Integer.class));
                if (rs.getTimestamp("created_date") != null) {
                    n.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
                }
                if (rs.getTimestamp("updated_date") != null) {
                    n.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
                }
                return n;
            };

    /**
     * notifyReportCompleted için iç projeksiyon: rapor + sahip + e-posta.
     */
    record ReportTarget(UUID reportId, UUID userId, String reportType, String email) {
    }
}
