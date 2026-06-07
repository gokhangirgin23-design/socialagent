package com.api.local;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.common.ResponseCode;

import lombok.RequiredArgsConstructor;

/**
 * LOCAL profil iç-test uçları (sadece local profilde oluşur — @Profile("local")).
 * Tüm uçlar POST (CLAUDE.md Madde 2) ve HTTP 200 + responseCode (Madde 3) konvansiyonuna uyar.
 *
 * Bu controller RabbitMQ'yu devre dışı bırakır: kuyruğu dinlemek yerine LocalJobRunner ile
 * user_job tablosundan FIFO job seçip gerçek pipeline'ı çalıştırır. Apify/OpenAI/Gemini
 * çağrıları local'de @Primary dummy bean'lerle taklit edildiğinden hiçbir dış servise gidilmez.
 *
 * Güvenlik: SecurityConfig'te /local/** permitAll yapıldı (controller zaten yalnızca local'de var).
 * userId istekten alınmaz; pipeline job kaydındaki user_id'yi kullanır.
 */
@RestController
@RequestMapping("/local")
@Profile("local")
@RequiredArgsConstructor
public class LocalWorkerController {

	// RabbitMQ'suz iş çalıştırıcı
	private final LocalJobRunner localJobRunner;

	/**
	 * Sıradaki (en eski) bekleyen job'ı işler.
	 * Bekleyen job yoksa responseCode = NOT_FOUND (data null).
	 */
	@PostMapping("/run-next-job")
	public DataResponse<UUID> runNextJob() {
		UUID jobId = localJobRunner.runNextJob();
		if (jobId == null) {
			return DataResponse.of(ResponseCode.NOT_FOUND);
		}
		return DataResponse.success(jobId);
	}

	/**
	 * Body ile verilen belirli bir job'ı işler (FIFO sırasına bakmaz).
	 */
	@PostMapping("/run-job")
	public DataResponse<UUID> runJob(@RequestBody RunJobRequest request) {
		if (request == null || request.userJobId() == null) {
			return DataResponse.of(ResponseCode.VALIDATION_ERROR);
		}
		localJobRunner.runJob(request.userJobId());
		return DataResponse.success(request.userJobId());
	}

	/**
	 * Bekleyen tüm job'ları FIFO sırayla işler (güvenlik tavanı LocalJobRunner.MAX_BATCH).
	 */
	@PostMapping("/run-all-pending")
	public DataResponse<List<UUID>> runAllPending() {
		return DataResponse.success(localJobRunner.runAllPending());
	}

	/**
	 * Bekleyen (active=1, completed=0) job'ları FIFO sırasıyla listeler (işlemeden).
	 */
	@PostMapping("/pending")
	public DataResponse<List<LocalJobRunner.PendingJob>> pending() {
		return DataResponse.success(localJobRunner.listPending());
	}

	/**
	 * /local/run-job için basit istek gövdesi.
	 *
	 * @param userJobId işlenecek job id'si
	 */
	public record RunJobRequest(UUID userJobId) {
	}
}
