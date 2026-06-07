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
 * report yazma + durum akışı yönetimi (FAZ 7 — CLAUDE.md Bölüm 11). Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Her job için TEK rapor tutulur: kayıt yoksa insert (JPA save), varsa aynı kayıt yenilenir. "Job'ın raporu var mı" lookup'ı JdbcTemplate native; durum geçişleri (GENERATING/COMPLETED/ FAILED) native
 * UPDATE ile yapılır (CLAUDE.md Madde 6 — insert JPA, join/lookup/update native).
 *
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
	 * Bu job için var olan rapor kaydının id'sini döndürür (yoksa null). Her job için tek rapor tutulduğundan, varsa aynı kayıt yenilenir (recurring job'larda).
	 *
	 * @return mevcut report_id ya da null
	 */
	@Transactional(readOnly = true)
	public UUID findReportIdByJob(UUID userJobId) {
		// user_job_id ile mevcut rapor id'lerini ara (en yeni önce)
		String sql = """
				SELECT report_id
				FROM report
				WHERE user_job_id = ?
				ORDER BY created_date DESC
				""";
		List<UUID> rows = jdbcTemplate.query(sql,
				(rs, rowNum) -> rs.getObject("report_id", UUID.class),
				userJobId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * Job için yeni bir rapor kaydını PENDING durumunda oluşturur (JPA save).
	 *
	 * @return oluşturulan report_id
	 */
	@Transactional
	public UUID createPending(UUID userJobId) {
		// Yeni rapor kaydı (PENDING; içerik henüz yok)
		LocalDateTime now = LocalDateTime.now();
		Report report = new Report();
		report.setReportId(UUID.randomUUID());
		report.setUserJobId(userJobId);
		report.setStatus(ReportStatus.PENDING.name());
		report.setReportContent(null);
		report.setCreatedDate(now);
		report.setUpdatedDate(now);

		// JPA save ile insert
		reportRepository.saveAndFlush(report);
		log.info("report oluşturuldu (PENDING): userJobId={}, reportId={}", userJobId, report.getReportId());
		return report.getReportId();
	}

	/**
	 * Job için rapor kaydını garanti eder: varsa id'sini döndürür, yoksa PENDING olarak oluşturur.
	 *
	 * @return kullanılacak report_id
	 */
	@Transactional
	public UUID ensureReport(UUID userJobId) {
		UUID existing = findReportIdByJob(userJobId);
		// Mevcut rapor varsa onu yenile (recurring job); yoksa yeni oluştur
		return (existing != null) ? existing : createPending(userJobId);
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
	 * Durum + (opsiyonel) içerik güncellemesini native UPDATE ile uygular. content null ise yalnızca durum güncellenir (mevcut içerik bozulmaz).
	 */
	private void updateStatus(UUID reportId, ReportStatus status, String content) {
		LocalDateTime now = LocalDateTime.now();
		if (content == null) {
			// Yalnızca durum + updated_date güncelle
			String sql = """
					UPDATE report
					SET status = ?, updated_date = ?
					WHERE report_id = ?
					""";
			jdbcTemplate.update(sql, status.name(), Timestamp.valueOf(now), reportId);
		} else {
			// Durum + Markdown içerik + updated_date güncelle
			String sql = """
					UPDATE report
					SET status = ?, report_content = ?, updated_date = ?
					WHERE report_id = ?
					""";
			jdbcTemplate.update(sql, status.name(), content, Timestamp.valueOf(now), reportId);
		}
	}
}
