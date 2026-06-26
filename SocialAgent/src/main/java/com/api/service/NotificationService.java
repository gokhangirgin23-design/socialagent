package com.api.service;

import java.sql.Timestamp;
import java.time.LocalDate;
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

    // Markdown → PDF dönüştürücü (mail eki)
    private final ReportPdfService reportPdfService;

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
        String plainMessage = modeText + " raporunuz oluşturuldu. Detay için panele göz atın.";

        // PDF eki üret (hata olursa null → ek olmadan devam edilir)
        byte[] pdfBytes = null;
        try {
            pdfBytes = reportPdfService.generatePdf(target.reportContent(), modeText + " Raporu");
        } catch (Exception ex) {
            log.warn("PDF üretimi başarısız (mail eki olmadan devam): requestId={}, hata={}", requestId, ex.getMessage());
        }

        // HTML mail gövdesi + Spectiqs logosu
        String htmlBody = buildMailHtml(target, modeText, plainMessage);
        String pdfFileName = "spectiqs-rapor-" + LocalDate.now() + ".pdf";

        // Her kanalı ayrı ayrı gönder; sonucu ayrı notification satırına yaz.
        SendResult mailRes = mailSender.send(target.email(), title, htmlBody, pdfBytes, pdfFileName);
        saveChannelNotification(target, title, plainMessage, NotificationChannel.MAIL, mailRes, now);

        SendResult pushRes = pushSender.send(target.userId(), title, plainMessage);
        saveChannelNotification(target, title, plainMessage, NotificationChannel.PUSH_NOTIFICATION, pushRes, now);
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
        // report_content de çekiliyor: PDF eki ve mail gövdesi için
        String sql = """
                SELECT r.report_id, rr.user_id, rr.report_type, u.email, r.report_content
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
                rs.getString("email"),
                rs.getString("report_content")),
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
     * Bildirim için iç projeksiyon: rapor + sahip + e-posta + Markdown içerik.
     */
    record ReportTarget(UUID reportId, UUID userId, String reportType, String email, String reportContent) {
    }

    /**
     * Spectiqs markalı HTML mail gövdesi üretir.
     * Logo: gradient arka planlı köşeli kare + "S" harfi (SVG yoksa metin simgesi).
     */
    private String buildMailHtml(ReportTarget target, String modeText, String plainMessage) {
        String dateStr = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy",
                new java.util.Locale("tr", "TR")));
        return """
                <!DOCTYPE html>
                <html lang="tr">
                <head><meta charset="UTF-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/></head>
                <body style="margin:0;padding:0;background:#F5F7FC;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#F5F7FC;padding:32px 0;">
                    <tr><td align="center">
                      <table width="580" cellpadding="0" cellspacing="0"
                             style="background:#FFFFFF;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(15,23,42,0.08);">
                        <!-- Başlık -->
                        <tr>
                          <td style="background:linear-gradient(135deg,#111A2B 0%%,#1E2B47 100%%);padding:28px 32px;">
                            <table cellpadding="0" cellspacing="0">
                              <tr>
                                <td style="width:44px;height:44px;border-radius:12px;background:linear-gradient(150deg,#FFB224,#E8890C);
                                           text-align:center;vertical-align:middle;font-size:22px;font-weight:900;
                                           color:#080D18;line-height:44px;margin-right:12px;">S</td>
                                <td style="padding-left:12px;">
                                  <div style="font-size:22px;font-weight:700;color:#EAF0FB;letter-spacing:-0.02em;">Spectiqs</div>
                                  <div style="font-size:11px;color:#94A2BF;margin-top:2px;">See What Others Miss.</div>
                                </td>
                              </tr>
                            </table>
                          </td>
                        </tr>
                        <!-- İçerik -->
                        <tr>
                          <td style="padding:32px 32px 24px;">
                            <div style="font-size:11px;color:#8593AC;letter-spacing:0.08em;text-transform:uppercase;
                                        border-left:3px solid #FFB224;padding-left:10px;margin-bottom:20px;">
                              Rapor Hazır &nbsp;·&nbsp; %s
                            </div>
                            <h2 style="font-size:22px;font-weight:700;color:#0F1729;margin:0 0 12px;line-height:1.3;">
                              Raporunuz oluşturuldu 🎉
                            </h2>
                            <p style="font-size:14px;color:#51607A;line-height:1.7;margin:0 0 20px;">
                              <strong>%s</strong> analiziniz tamamlandı.
                              Detaylı sonuçlara Spectiqs panelinizden ulaşabilirsiniz.
                              Raporun PDF versiyonunu bu e-postanın ekinde bulabilirsiniz.
                            </p>
                            <!-- CTA -->
                            <table cellpadding="0" cellspacing="0" style="margin:24px 0;">
                              <tr>
                                <td style="background:#FFB224;border-radius:10px;">
                                  <a href="https://www.spectiqs.com/app/dashboard"
                                     style="display:inline-block;padding:13px 28px;font-size:14px;
                                            font-weight:700;color:#080D18;text-decoration:none;">
                                    Paneli Aç →
                                  </a>
                                </td>
                              </tr>
                            </table>
                            <p style="font-size:13px;color:#8593AC;line-height:1.6;margin:0;">
                              Bu e-posta %s tarihinde otomatik olarak gönderilmiştir.
                              Herhangi bir sorunuz için destek ekibimize ulaşabilirsiniz.
                            </p>
                          </td>
                        </tr>
                        <!-- Alt bilgi -->
                        <tr>
                          <td style="background:#F5F7FC;padding:16px 32px;border-top:1px solid #E0E7F0;">
                            <p style="font-size:11px;color:#8593AC;margin:0;text-align:center;">
                              © Spectiqs Analytics &nbsp;·&nbsp; spectiqs.com<br/>
                              Bu bildirimi almak istemiyorsanız hesap ayarlarınızdan devre dışı bırakabilirsiniz.
                            </p>
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(modeText, modeText, dateStr);
    }
}
