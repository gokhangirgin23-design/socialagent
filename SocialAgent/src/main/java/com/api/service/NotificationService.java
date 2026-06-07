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
 * Bildirim iş mantığı (FAZ 8 — CLAUDE.md Bölüm 12).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Sorumluluklar:
 *   1) notifyReportCompleted: rapor COMPLETED olunca DB'ye notification yaz + mail + push.
 *   2) listNotifications: kullanıcının bildirimlerini sayfalı listele (dashboard).
 *   3) markAsRead: bir bildirimi okundu işaretle (yalnızca sahibi).
 *   4) unreadCount: okunmamış bildirim sayısı (dashboard rozeti).
 *
 * insert -> JPA save; lookup/join/update/count -> JdbcTemplate native + text-block + "?" (CLAUDE.md Madde 6).
 * notification tablosunda unique kısıt yoktur; bu yüzden insert öncesi ek unique kontrolü gerekmez (Madde 5).
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

	// E-posta gönderim adaptörü (yapılandırma yoksa no-op)
	private final MailSender mailSender;

	// Push gönderim adaptörü (stub; varsayılan kapalı)
	private final PushSender pushSender;

	/**
	 * Bir job'ın raporu COMPLETED olduğunda kullanıcıya bildirim üretir:
	 *   - notification kaydı insert (reference_type=REPORT, reference_id=report_id, is_read=0),
	 *   - mail + push gönderimi (yapılandırma yoksa sessizce atlanır).
	 *
	 * Bağımsız transaction (REQUIRES_NEW): bildirim adımındaki bir DB hatası, çağıran scraping
	 * pipeline'ının transaction'ını KİRLETMEZ (rapor yazımı + iş sonu muhasebesi korunur).
	 * Çağıran taraf (ScrapePipelineService) bu metodu ayrıca try/catch ile sarmalar.
	 *
	 * @param userJobId raporu tamamlanan job
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void notifyReportCompleted(UUID userJobId) {
		// 1) Tamamlanmış raporu, sahibini ve e-postasını TEK native join ile çek.
		//    report ⋈ user_job ⋈ user_info — eski stil "=" (CLAUDE.md Madde 6).
		//    Yalnızca status='COMPLETED' raporlar için bildirim üretilir.
		ReportTarget target = loadCompletedReportTarget(userJobId);
		if (target == null) {
			// Tamamlanmış rapor yok (FAILED/boş tur) -> bildirim üretilmez
			log.info("Bildirim için tamamlanmış rapor bulunamadı: userJobId={}", userJobId);
			return;
		}

		// 2) Ortak bildirim metni
		LocalDateTime now = LocalDateTime.now();
		String title = "Raporunuz hazır";
		// analiz modu bilgisini metne kat (örn. "BOTH analiz raporunuz oluşturuldu.")
		String modeText = (target.analysisMode() != null) ? target.analysisMode() : "Analiz";
		String message = modeText + " raporunuz oluşturuldu. Detay için panele göz atın.";

		// 3) Her kanalı ayrı ayrı gönder; sonucu (success/error_detail) ayrı notification satırına yaz.
		//    Dashboard listesi/okunmamış sayımı PUSH_NOTIFICATION satırını gösterir (opsiyon b);
		//    MAIL satırı yalnız gönderim kaydı olarak tutulur.
		SendResult mailRes = mailSender.send(target.email(), title, message);
		saveChannelNotification(target, title, message, NotificationChannel.MAIL, mailRes, now);

		SendResult pushRes = pushSender.send(target.userId(), title, message);
		saveChannelNotification(target, title, message, NotificationChannel.PUSH_NOTIFICATION, pushRes, now);
	}

	/**
	 * Tek bir kanal için notification satırı yazar (JPA save).
	 * reference_type=REPORT, reference_id=report_id; success/error_detail kanal sonucundan gelir.
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
	 *
	 * @param onlyUnread true ise yalnızca okunmamışlar (is_read = 0)
	 */
	@Transactional(readOnly = true)
	public List<NotificationDto> listNotifications(UUID userId, int page, int size, boolean onlyUnread) {
		// Negatif sayfa/boyut koruması (JobService listJobs deseni)
		int safePage = Math.max(page, 0);
		int safeSize = (size > 0) ? size : 10;
		int offset = safePage * safeSize;

		// Temel sorgu (kullanıcı bazlı); dashboard yalnız PUSH_NOTIFICATION satırlarını gösterir (opsiyon b).
		// Böylece her rapor için tek bildirim görünür; MAIL satırı yalnız gönderim kaydıdır.
		// onlyUnread bayrağına göre okunmamış filtresi statik eklenir.
		// Kullanıcı girdisi (userId/size/offset) DAİMA "?" parametresi olarak geçer (injection güvenliği).
		String baseSql = """
				SELECT notification_id, user_id, title, message,
				       reference_type, reference_id, channel, success, error_detail,
				       is_read, created_date, updated_date
				FROM notification
				WHERE user_id = ?
				  AND channel = 'PUSH_NOTIFICATION'
				""";
		// onlyUnread ise yalnız okunmamışlar
		String filterSql = onlyUnread ? "AND is_read = 0\n" : "";
		// Sıralama + sayfalama
		String orderSql = """
				ORDER BY created_date DESC
				LIMIT ? OFFSET ?
				""";
		String sql = baseSql + filterSql + orderSql;
		// JdbcTemplate ile çek (? sırasıyla: userId, size, offset)
		List<Notification> rows = jdbcTemplate.query(sql, NOTIFICATION_ROW_MAPPER, userId, safeSize, offset);
		// MapStruct ile DTO listesine dönüştür
		return notificationMapper.toDtoList(rows);
	}

	/**
	 * Bir bildirimi okundu (is_read=1) işaretler. Yalnızca bildirimin SAHİBİ işaretleyebilir;
	 * userId koşulu sayesinde başka kullanıcının bildirimi etkilenmez (CLAUDE.md Madde 4).
	 * Endpoint: POST /notification/read
	 *
	 * @return işaretlenen satır sayısı (0 ise kullanıcının böyle bir bildirimi yok)
	 */
	@Transactional
	public int markAsRead(UUID userId, UUID notificationId) {
		LocalDateTime now = LocalDateTime.now();
		// userId koşulu ownership güvencesidir
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
	 * Job için tamamlanmış raporu, sahibini ve e-postasını native join ile çeker (yoksa null).
	 * report ⋈ user_job ⋈ user_info — eski stil "=" inner join (CLAUDE.md Madde 6).
	 * En yeni rapor önce gelir; tek satır alınır.
	 */
	private ReportTarget loadCompletedReportTarget(UUID userJobId) {
		String sql = """
				SELECT r.report_id, j.user_id, j.analysis_mode, u.email
				FROM report r, user_job j, user_info u
				WHERE r.user_job_id = j.user_job_id
				  AND j.user_id = u.user_id
				  AND r.user_job_id = ?
				  AND r.status = 'COMPLETED'
				ORDER BY r.created_date DESC
				""";
		List<ReportTarget> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new ReportTarget(
				rs.getObject("report_id", UUID.class),
				rs.getObject("user_id", UUID.class),
				rs.getString("analysis_mode"),
				rs.getString("email")),
				userJobId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	// notification satırını entity'ye çeviren RowMapper (liste sorgusu için)
	private static final org.springframework.jdbc.core.RowMapper<Notification> NOTIFICATION_ROW_MAPPER =
			(rs, rowNum) -> {
				Notification n = new Notification();
				n.setNotificationId(rs.getObject("notification_id", UUID.class));
				n.setUserId(rs.getObject("user_id", UUID.class));
				n.setTitle(rs.getString("title"));
				n.setMessage(rs.getString("message"));
				n.setReferenceType(rs.getString("reference_type"));
				// reference_id nullable olabilir
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
	 * Paket içi (public API'ye sızmaz); aynı paketteki birim testi inşa edebilsin diye package-private.
	 */
	record ReportTarget(UUID reportId, UUID userId, String analysisMode, String email) {
	}
}
