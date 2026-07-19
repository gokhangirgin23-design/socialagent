package com.api.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.ApiException;
import com.api.common.DataResponse;
import com.api.common.ResponseCode;
import com.api.config.AppProperties;
import com.api.service.ContentPipelineService;
import com.api.service.ReportRequestService;
import com.api.service.ScrapePipelineService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin işlemleri (JWT değil, X-Admin-Key header ile korumalı).
 * SecurityConfig'de /admin/** permitAll; doğrulama bu controller'da yapılır.
 * Service interface yok (CLAUDE.md Madde 1). Tüm endpoint'ler POST (CLAUDE.md Madde 2).
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

	// Admin anahtar yapılandırması + diğer app ayarları
	private final AppProperties appProperties;

	// Rapor isteği iş mantığı (requeue metodu buradan çağrılır)
	private final ReportRequestService reportRequestService;

	// Rapor kredi düşümü reconciliation (retryFailedDebits buradan çağrılır)
	private final ScrapePipelineService scrapePipelineService;

	// İçerik kredi düşümü reconciliation (retryFailedDebits buradan çağrılır)
	private final ContentPipelineService contentPipelineService;

	/**
	 * V2: Status-bazlı sweep — FAILED/PARTIAL veya takılı (30 dk+ PROCESSING/PENDING)
	 * rapor isteklerini attempt_count < 3 koşuluyla yeniden kuyruğa basar.
	 * Re-queue'da status='PENDING', attempt_count++ yapılır (poison guard).
	 *
	 * Header: X-Admin-Key → env ADMIN_KEY değeriyle eşleşmeli.
	 * Endpoint: POST /admin/requeue-stuck
	 */
	@PostMapping("/requeue-stuck")
	public DataResponse<Map<String, Integer>> requeueStuck(
			@RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {

		// Admin anahtarı doğrulama
		doğrulaAdminKey(adminKey);

		int requeuedCount = reportRequestService.requeueStuck();
		log.info("Admin requeue-stuck tamamlandı: requeuedCount={}", requeuedCount);
		return DataResponse.success(Map.of("requeuedCount", requeuedCount));
	}

	/**
	 * Reconciliation: rapor teslim edilmiş (status=COMPLETED) ama kredi düşümü hata aldığı
	 * için düşmemiş (credit_debited=0) kayıtları bulup düşümü tekrar dener.
	 * Bu durum, rapor üretimi ile kredi düşümünün ayrı transaction'larda olmasından kaynaklanır
	 * (bkz. ScrapePipelineService.debitOnCompleted) — dünkü olayda olduğu gibi rapor commit
	 * edilip kredi düşümü SQL hatasıyla başarısız olduğunda bu endpoint mutabakatı sağlar.
	 *
	 * Header: X-Admin-Key → env ADMIN_KEY değeriyle eşleşmeli.
	 * Endpoint: POST /admin/retry-failed-debits
	 */
	@PostMapping("/retry-failed-debits")
	public DataResponse<Map<String, Integer>> retryFailedDebits(
			@RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {

		doğrulaAdminKey(adminKey);

		int recoveredCount = scrapePipelineService.retryFailedDebits();
		log.info("Admin retry-failed-debits tamamlandı: recoveredCount={}", recoveredCount);
		return DataResponse.success(Map.of("recoveredCount", recoveredCount));
	}

	/**
	 * Reconciliation: içerik teslim edilmiş (status=COMPLETED) ama kredi düşümü hata aldığı
	 * için düşmemiş (credit_debited=0) kayıtları bulup düşümü tekrar dener.
	 * Bkz. ContentPipelineService.debitOnCompleted.
	 *
	 * Header: X-Admin-Key → env ADMIN_KEY değeriyle eşleşmeli.
	 * Endpoint: POST /admin/retry-failed-content-debits
	 */
	@PostMapping("/retry-failed-content-debits")
	public DataResponse<Map<String, Integer>> retryFailedContentDebits(
			@RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {

		doğrulaAdminKey(adminKey);

		int recoveredCount = contentPipelineService.retryFailedDebits();
		log.info("Admin retry-failed-content-debits tamamlandı: recoveredCount={}", recoveredCount);
		return DataResponse.success(Map.of("recoveredCount", recoveredCount));
	}

	/**
	 * X-Admin-Key header'ını yapılandırılmış ADMIN_KEY ile karşılaştırır.
	 * Eşleşmezse veya anahtar boşsa UNAUTHORIZED fırlatır.
	 */
	private void doğrulaAdminKey(String headerKey) {
		String configured = appProperties.getAdmin().getKey();
		if (configured == null || configured.isBlank()) {
			// ADMIN_KEY env'i set edilmemiş → endpoint devre dışı
			throw new ApiException(ResponseCode.UNAUTHORIZED, "Admin anahtarı yapılandırılmamış (ADMIN_KEY env eksik)");
		}
		if (!configured.equals(headerKey)) {
			throw new ApiException(ResponseCode.UNAUTHORIZED, "Geçersiz veya eksik X-Admin-Key");
		}
	}
}
