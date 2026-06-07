package com.api.scheduler;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.api.messaging.JobQueueProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Job zamanlayıcı (FAZ 4 — CLAUDE.md Bölüm 9).
 * Periyodik olarak active=1 AND completed=0 olan job'ları JdbcTemplate ile çeker
 * ve RabbitMQ'ya basar. Lookup'lar native + text-block SQL + ? param (CLAUDE.md Madde 6).
 *
 * Çift instance (docker-compose app1/app2) ve sonsuz yeniden-kuyruklama problemine karşı
 * ATOMİK CLAIM deseni kullanılır:
 *   1) Aday job id'leri seç (active=1, completed=0, queued=0).
 *   2) Her aday için şartlı UPDATE (... AND queued=0) ile "claim" et; sadece 1 satır
 *      etkilenirse o instance kuyruğa basma hakkını kazanır (DB hakem).
 *   3) Kuyruğa basma başarısızsa claim'i geri al (queued=0).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Component.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class JobScheduler {

	// Native sorgular için JdbcTemplate (CLAUDE.md Madde 6)
	private final JdbcTemplate jdbcTemplate;

	// Kuyruğa basan producer
	private final JobQueueProducer jobQueueProducer;

	/**
	 * Uygun job'ları tarar ve kuyruğa basar.
	 * Çalışma aralığı app.scheduler.poll-interval-ms ile yönetilir (varsayılan 30 sn).
	 * fixedDelayString: bir çalışma bitince beklenen süre (üst üste binmez).
	 */
	@Scheduled(fixedDelayString = "${app.scheduler.poll-interval-ms:30000}")
	public void pollAndQueueJobs() {
		// 1) Henüz kuyruğa basılmamış uygun job adaylarını çek
		//    Scheduler bu sorguda idx_user_job_active_completed index'ini kullanır.
		//    FAZ 7: next_run_date filtresi — NULL (hemen uygun) ya da zamanı gelmiş job'lar.
		//    Rapor kapısı (last_report_date) Java tarafında uygulanır: "son analysis_period_days gün"
		//    karşılaştırması H2/PostgreSQL'de tek dialektte güvenli yazılamadığından (INTERVAL vs DATEADD).
		//    CURRENT_TIMESTAMP kullanılır (bound param yok); H2 (MODE=PostgreSQL) ve PostgreSQL uyumlu.
		String selectSql = """
				SELECT user_job_id, last_report_date, analysis_period_days
				FROM user_job
				WHERE active = 1 AND completed = 0 AND queued = 0
				  AND (next_run_date IS NULL OR next_run_date <= CURRENT_TIMESTAMP)
				ORDER BY created_date ASC
				""";
		List<Candidate> candidates = jdbcTemplate.query(selectSql, CANDIDATE_ROW_MAPPER);

		// Aday yoksa erken çık (gürültüsüz)
		if (candidates.isEmpty()) {
			log.debug("Kuyruğa basılacak uygun job bulunamadı.");
			return;
		}

		// Bu turda başarıyla kuyruğa basılan job sayısı
		int queuedCount = 0;

		// 2) Her aday için rapor kapısı + atomik claim + publish
		LocalDateTime now = LocalDateTime.now();
		for (Candidate c : candidates) {
			UUID jobId = c.jobId();
			// Rapor kapısı: son analysis_period_days gün içinde rapor üretildiyse kuyruğa hiç basma
			if (recentlyReported(c, now)) {
				log.debug("Rapor kapısı: job son {} günde raporlanmış, kuyruğa basılmıyor: userJobId={}",
						c.analysisPeriodDays(), jobId);
				continue;
			}
			// Tek satırda claim: yalnızca hâlâ queued=0 ise sahiplen
			// Çift instance senaryosunda yalnızca bir instance 1 satır günceller
			if (!tryClaim(jobId)) {
				// Başka bir instance/önceki tur bu job'ı zaten almış; atla
				log.debug("Job zaten claim edilmiş, atlanıyor: userJobId={}", jobId);
				continue;
			}
			try {
				// Claim başarılı -> kuyruğa bas
				jobQueueProducer.publishJob(jobId);
				queuedCount++;
			} catch (Exception ex) {
				// Broker erişilemez vb. -> claim'i geri al ki sonraki turda tekrar denensin
				releaseClaim(jobId);
				log.warn("Job kuyruğa basılamadı, claim geri alındı: userJobId={}, hata={}",
						jobId, ex.getMessage());
			}
		}

		// Tur özeti
		log.info("Scheduler turu tamamlandı: aday={}, kuyruğa basılan={}", candidates.size(), queuedCount);
	}

	/**
	 * Rapor kapısı: job son analysis_period_days gün içinde rapor ürettiyse true (kuyruğa basılmamalı).
	 * last_report_date NULL ise hiç raporlanmamış demektir -> false (uygun).
	 */
	private boolean recentlyReported(Candidate c, LocalDateTime now) {
		if (c.lastReportDate() == null) {
			return false;
		}
		// analysis_period_days null gelirse 3 varsay
		int days = (c.analysisPeriodDays() != null) ? c.analysisPeriodDays() : 3;
		// Son rapor, (now - days) anından daha yeniyse pencere içindedir
		return c.lastReportDate().isAfter(now.minusDays(days));
	}

	// Aday job satırını taşıyan kayıt (rapor kapısı için last_report_date + analysis_period_days dahil).
	// Test (aynı paket) bunu doğrudan kurabilsin diye package-private.
	record Candidate(UUID jobId, LocalDateTime lastReportDate, Integer analysisPeriodDays) {
	}

	// Aday satır -> Candidate dönüştürücü
	private static final org.springframework.jdbc.core.RowMapper<Candidate> CANDIDATE_ROW_MAPPER =
			(rs, rowNum) -> new Candidate(
					rs.getObject("user_job_id", UUID.class),
					rs.getTimestamp("last_report_date") != null
							? rs.getTimestamp("last_report_date").toLocalDateTime() : null,
					rs.getObject("analysis_period_days", Integer.class));

	/**
	 * Job'ı atomik olarak claim eder (queued=0 -> 1).
	 *
	 * @return yalnızca 1 satır güncellendiyse true (claim bu instance'a ait)
	 */
	private boolean tryClaim(UUID jobId) {
		LocalDateTime now = LocalDateTime.now();
		// Şartlı UPDATE: yalnızca hâlâ kuyruğa basılmamışsa sahiplen
		String claimSql = """
				UPDATE user_job
				SET queued = 1, queued_date = ?, updated_date = ?
				WHERE user_job_id = ? AND queued = 0
				""";
		int affected = jdbcTemplate.update(claimSql,
				Timestamp.valueOf(now), Timestamp.valueOf(now), jobId);
		// 1 satır etkilendiyse claim başarılı
		return affected == 1;
	}

	/**
	 * Publish başarısız olursa claim'i geri alır (queued=1 -> 0).
	 * Böylece job bir sonraki tarama turunda yeniden denenir.
	 */
	private void releaseClaim(UUID jobId) {
		LocalDateTime now = LocalDateTime.now();
		String releaseSql = """
				UPDATE user_job
				SET queued = 0, queued_date = NULL, updated_date = ?
				WHERE user_job_id = ?
				""";
		jdbcTemplate.update(releaseSql, Timestamp.valueOf(now), jobId);
	}
}
