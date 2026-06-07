package com.api.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.api.entity.JobPeriod;
import com.api.entity.UserJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * İş sonu muhasebesi + periyot bazlı yeniden zamanlama (FAZ 7 — CLAUDE.md Bölüm 8, 9).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Worker pipeline'ı (scraping + analiz + rapor) bittikten sonra çağrılır. Yaptıkları:
 *   1) current_count++ (bu çalışma sayıldı).
 *   2) Tamamlanma kararı:
 *        - ON_DEMAND               -> completed=1 (tek seferlik).
 *        - repeat_count dolu & current_count >= repeat_count -> completed=1.
 *        - aksi halde              -> completed=0 (devam eden recurring job).
 *   3) queued reset (queued=0, queued_date=NULL) -> scheduler bir sonraki uygun zamanda yeniden alabilir.
 *   4) Periyot bazlı yeniden zamanlama: devam eden job için next_run_date = now + periyot
 *      (DAILY/WEEKLY/MONTHLY). Böylece WEEKLY job her tarama turunda değil, periyodu dolunca tetiklenir
 *      (scheduler next_run_date'i filtreler — FAZ 4 sorgusu FAZ 7'de güncellendi).
 *
 * Lookup + UPDATE native (JdbcTemplate + text-block + "?" — CLAUDE.md Madde 6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobCompletionService {

	// Native lookup + UPDATE için JdbcTemplate
	private final JdbcTemplate jdbcTemplate;

	/**
	 * Bir çalışma bittikten sonra job'ın durum kolonlarını günceller.
	 * Job bulunamazsa sessizce çıkar (mesaj zaten ack edilecektir).
	 *
	 * @param userJobId tamamlanan çalışmanın job'ı
	 */
	// REQUIRES_NEW: dış transaction rollback olsa bile bu güncelleme kaybolmaz.
	// processJob finally bloğundan çağrılır; pipeline hatası job'ı queued=1'de tıkamamalı.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finalizeJob(UUID userJobId) {
		// 1) Muhasebe için gerekli kolonları yükle
		UserJob job = loadJobForFinalize(userJobId);
		if (job == null) {
			// Job silinmiş/yok -> muhasebe yapılmaz
			log.warn("Muhasebe için job bulunamadı: userJobId={}", userJobId);
			return;
		}

		// 2) current_count++ (null -> 0 kabul)
		int newCount = (job.getCurrentCount() != null ? job.getCurrentCount() : 0) + 1;

		// 3) Tamamlanma kararı (CLAUDE.md Bölüm 8)
		boolean completed = decideCompleted(job.getJobPeriod(), job.getRepeatCount(), newCount);

		// 4) Yeniden zamanlama: tamamlandıysa next_run yok; devam ediyorsa periyot kadar ileri
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime nextRun = completed ? null : computeNextRun(job.getJobPeriod(), now);

		// 5) Tek UPDATE ile durum kolonlarını yaz (queued reset + tamamlanınca active=0)
		String sql = """
				UPDATE user_job
				SET current_count = ?,
				    completed = ?,
				    active = ?,
				    queued = 0,
				    queued_date = NULL,
				    next_run_date = ?,
				    updated_date = ?
				WHERE user_job_id = ?
				""";
		jdbcTemplate.update(sql,
				newCount,
				completed ? 1 : 0,
				completed ? 0 : 1,
				(nextRun != null ? Timestamp.valueOf(nextRun) : null),
				Timestamp.valueOf(now),
				userJobId);

		log.info("İş sonu muhasebesi: userJobId={}, currentCount={}, completed={}, active={}, nextRun={}",
				userJobId, newCount, completed, completed ? 0 : 1, nextRun);
	}

	/**
	 * Tamamlanma kuralını uygular (CLAUDE.md Bölüm 8).
	 *  - ON_DEMAND -> her zaman tamamlanır.
	 *  - repeat_count dolu ve current_count >= repeat_count -> tamamlanır.
	 *  - aksi halde devam eder.
	 */
	private boolean decideCompleted(String jobPeriodRaw, Integer repeatCount, int currentCount) {
		// ON_DEMAND tek seferliktir
		if (JobPeriod.ON_DEMAND.name().equals(jobPeriodRaw)) {
			return true;
		}
		// repeat_count tanımlıysa hedefe ulaşınca tamamlanır
		if (repeatCount != null && currentCount >= repeatCount) {
			return true;
		}
		// Aksi halde recurring olarak devam eder
		return false;
	}

	/**
	 * Job periyoduna göre bir sonraki çalışma zamanını hesaplar.
	 * Tanınmayan/eksik periyot -> null (hemen uygun: scheduler NULL'ı geçer).
	 */
	private LocalDateTime computeNextRun(String jobPeriodRaw, LocalDateTime from) {
		// Periyot null/boşsa hemen uygun bırak (NULL)
		if (jobPeriodRaw == null || jobPeriodRaw.isBlank()) {
			return null;
		}
		try {
			JobPeriod period = JobPeriod.valueOf(jobPeriodRaw);
			return switch (period) {
				case DAILY -> from.plusDays(1);
				case WEEKLY -> from.plusWeeks(1);
				case MONTHLY -> from.plusMonths(1);
				// ON_DEMAND bu noktaya gelmez (completed=true); güvenlik için NULL
				case ON_DEMAND -> null;
			};
		} catch (IllegalArgumentException ex) {
			// Bilinmeyen periyot değeri -> hemen uygun (NULL)
			log.warn("Bilinmeyen job_period '{}', next_run_date NULL bırakıldı.", jobPeriodRaw);
			return null;
		}
	}

	/**
	 * Muhasebe için job_period + repeat_count + current_count kolonlarını yükler.
	 * Bulunamazsa null.
	 */
	private UserJob loadJobForFinalize(UUID userJobId) {
		String sql = """
				SELECT user_job_id, job_period, repeat_count, current_count
				FROM user_job
				WHERE user_job_id = ?
				""";
		List<UserJob> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
			UserJob j = new UserJob();
			j.setUserJobId(rs.getObject("user_job_id", UUID.class));
			j.setJobPeriod(rs.getString("job_period"));
			j.setRepeatCount(rs.getObject("repeat_count", Integer.class));
			j.setCurrentCount(rs.getObject("current_count", Integer.class));
			return j;
		}, userJobId);
		return rows.isEmpty() ? null : rows.get(0);
	}
}
