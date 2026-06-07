package com.api.local;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.api.service.ScrapePipelineService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * LOCAL profil iş çalıştırıcısı — JobWorker'ın (FAZ 5) RabbitMQ'suz karşılığı (sadece iç test).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service. Sadece local profilde oluşur.
 *
 * JobWorker'dan FARKI:
 *   - Kuyruk DİNLEMEZ. Mesaj yerine user_job tablosundan FIFO mantığıyla job seçer
 *     (prod scheduler ile aynı kurallar: active=1, completed=0, queued=0, next_run_date geldi,
 *      rapor kapısı son analysis_period_days gün içinde raporlanmamış).
 *   - Job'ı bulduktan sonra aynı gerçek pipeline'ı çağırır: ScrapePipelineService.processJob.
 *     (Apify/OpenAI/Gemini çağrıları @Primary dummy bean'lerle local'de taklit edilir.)
 *
 * Not: created_date sıralaması FIFO'yu sağlar. runNextJob/runAllPending/pending prod filtrelerini uygular;
 * runJob(id) ise filtreleri ATLAR (belirli job'ı zorla çalıştırma — manuel test için). active=1 şartı,
 * pipeline'ın (loadJob) zaten pasif job'ı reddedeceği için baştan eklenmiştir.
 */
@Slf4j
@Service
@Profile("local")
@RequiredArgsConstructor
public class LocalJobRunner {

	// FIFO job seçimi için JdbcTemplate native (CLAUDE.md Madde 6)
	private final JdbcTemplate jdbcTemplate;

	// Worker ile aynı gerçek pipeline (local'de dış çağrılar dummy)
	private final ScrapePipelineService scrapePipelineService;

	// Tek tetiklemede en fazla işlenecek job (run-all güvenlik tavanı)
	private static final int MAX_BATCH = 50;

	/**
	 * Sıradaki (en eski) bekleyen job'ı işler.
	 *
	 * @return işlenen job'ın id'si; bekleyen job yoksa null
	 */
	public UUID runNextJob() {
		UUID jobId = findNextPendingJobId();
		if (jobId == null) {
			log.info("[LOCAL] İşlenecek bekleyen job yok (completed=0).");
			return null;
		}
		log.info("[LOCAL] Job işleniyor (FIFO): userJobId={}", jobId);
		// Worker ile birebir aynı çağrı
		scrapePipelineService.processJob(jobId);
		return jobId;
	}

	/**
	 * Belirli bir job'ı id ile işler (bekleme sırasına bakmaksızın).
	 *
	 * @param userJobId işlenecek job
	 */
	public void runJob(UUID userJobId) {
		log.info("[LOCAL] Job işleniyor (id ile): userJobId={}", userJobId);
		scrapePipelineService.processJob(userJobId);
	}

	/**
	 * Bekleyen tüm job'ları FIFO sırayla, hiç bekleyen kalmayana kadar işler (tavan: MAX_BATCH).
	 * Her job finalizeJob ile completed=1 olabileceğinden döngü ilerler; ON_DEMAND olmayan
	 * recurring job'lar completed=0 kalabilir ve döngü onları tekrar seçebileceğinden,
	 * sonsuz döngüyü önlemek için işlenen id'ler atlanır.
	 *
	 * @return işlenen job id listesi (FIFO sırasıyla)
	 */
	public List<UUID> runAllPending() {
		java.util.List<UUID> processed = new java.util.ArrayList<>();
		java.util.Set<UUID> seen = new java.util.HashSet<>();
		for (int i = 0; i < MAX_BATCH; i++) {
			UUID jobId = findNextPendingJobId(seen);
			if (jobId == null) {
				break;
			}
			seen.add(jobId);
			log.info("[LOCAL] Toplu işleme {}/{}: userJobId={}", i + 1, MAX_BATCH, jobId);
			scrapePipelineService.processJob(jobId);
			processed.add(jobId);
		}
		log.info("[LOCAL] Toplu işleme tamamlandı: işlenen={}", processed.size());
		return processed;
	}

	/**
	 * Bekleyen (active=1, completed=0) job'ların id + created_date listesini döndürür (FIFO).
	 *
	 * @return bekleyen job özetleri (en eski önce)
	 */
	public List<PendingJob> listPending() {
		// Uygun (active=1, completed=0, queued=0, next_run_date geldi, rapor kapısı açık) job'ları döndür
		return loadEligibleJobs().stream()
				.map(j -> new PendingJob(j.jobId(), j.createdDate()))
				.toList();
	}

	// ============================================================
	// Yardımcılar
	// ============================================================

	/** En eski uygun job id'sini döndürür; yoksa null. */
	private UUID findNextPendingJobId() {
		return findNextPendingJobId(java.util.Set.of());
	}

	/** 'exclude' içindekiler hariç en eski uygun job id'sini döndürür; yoksa null. */
	private UUID findNextPendingJobId(java.util.Set<UUID> exclude) {
		for (EligibleJob j : loadEligibleJobs()) {
			if (!exclude.contains(j.jobId())) {
				return j.jobId();
			}
		}
		return null;
	}

	/**
	 * Uygun job'ları prod scheduler ile AYNI kurallarla döndürür (FIFO):
	 *   - active=1 AND completed=0 AND queued=0
	 *   - next_run_date NULL ya da zamanı gelmiş
	 *   - rapor kapısı: son analysis_period_days gün içinde raporlanan job atlanır (Java-side, portability).
	 * runJob(id) bu filtreleri ATLAR (belirli job'ı zorla çalıştırma — manuel test için).
	 */
	private List<EligibleJob> loadEligibleJobs() {
		String sql = """
				SELECT user_job_id, created_date, last_report_date, analysis_period_days
				FROM user_job
				WHERE active = 1 AND completed = 0 AND queued = 0
				  AND (next_run_date IS NULL OR next_run_date <= CURRENT_TIMESTAMP)
				ORDER BY created_date ASC
				""";
		List<EligibleJob> all = jdbcTemplate.query(sql, (rs, rowNum) -> new EligibleJob(
				rs.getObject("user_job_id", UUID.class),
				rs.getTimestamp("created_date") != null ? rs.getTimestamp("created_date").toLocalDateTime() : null,
				rs.getTimestamp("last_report_date") != null ? rs.getTimestamp("last_report_date").toLocalDateTime() : null,
				rs.getObject("analysis_period_days", Integer.class)));

		LocalDateTime now = LocalDateTime.now();
		List<EligibleJob> eligible = new java.util.ArrayList<>();
		for (EligibleJob j : all) {
			// Rapor kapısı: son analysis_period_days gün içinde raporlandıysa atla
			if (j.lastReportDate() != null) {
				int days = (j.analysisPeriodDays() != null) ? j.analysisPeriodDays() : 3;
				if (j.lastReportDate().isAfter(now.minusDays(days))) {
					continue;
				}
			}
			eligible.add(j);
		}
		return eligible;
	}

	/** Uygun job satırı (FIFO + rapor kapısı değerlendirmesi için iç taşıyıcı). */
	private record EligibleJob(UUID jobId, LocalDateTime createdDate,
			LocalDateTime lastReportDate, Integer analysisPeriodDays) {
	}

	/**
	 * Bekleyen job özeti (id + oluşturulma tarihi).
	 *
	 * @param userJobId  job id
	 * @param createdDate oluşturulma tarihi (FIFO kriteri)
	 */
	public record PendingJob(UUID userJobId, LocalDateTime createdDate) {
	}
}
