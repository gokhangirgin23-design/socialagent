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
import com.api.service.ReportRequestService;

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

	/**
	 * Sunucu yeniden başlaması veya broker hatası nedeniyle kuyruğa girememiş
	 * (report_request tablosunda olup report tablosunda hiç kaydı olmayan)
	 * rapor isteklerini yeniden kuyruğa basar.
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
