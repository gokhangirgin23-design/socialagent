package com.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.AddMonitoredAccountRequest;
import com.api.dto.AddOwnAccountRequest;
import com.api.dto.MonitoredAccountDto;
import com.api.dto.RemoveMonitoredAccountRequest;
import com.api.dto.UserSocialAccountDto;
import com.api.security.SecurityUtil;
import com.api.service.AccountService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Hesap yönetimi uçları: kendi hesabı ve izlenen (rakip) hesaplar (hepsi POST — CLAUDE.md Madde 2).
 * Tüm uçlar JWT gerektiren güvenli uçlardır.
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 */
@Tag(name = "Hesap", description = "Kullanıcının kendi ve rakip (izlenen) sosyal hesap yönetimi")
@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {

	// Hesap iş mantığı
	private final AccountService accountService;

	/**
	 * Kullanıcının kendi sosyal medya hesabını ekler.
	 * Onboarding adım 5 (opsiyonel).
	 */
	@Operation(summary = "Kendi hesabı ekle developp", description = "Kullanıcının kendi Instagram hesabını ekler (OWN_ONLY/BOTH analizleri için).")
	@PostMapping("/own/add")
	public DataResponse<UserSocialAccountDto> addOwnAccount(@Valid @RequestBody AddOwnAccountRequest request) {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// İş katmanına devret; eklenen hesabı DTO olarak al
		UserSocialAccountDto result = accountService.addOwnAccount(userId, request);
		return DataResponse.success(result);
	}

	/**
	 * Kullanıcının aktif kendi hesabını getirir.
	 */
	@Operation(summary = "Kendi hesabı getir", description = "Kullanıcının ekli kendi hesabını döndürür.")
	@PostMapping("/own/get")
	public DataResponse<UserSocialAccountDto> getOwnAccount() {
		// userId JWT'den al; request body gerekmez
		UUID userId = SecurityUtil.getCurrentUserId();
		UserSocialAccountDto result = accountService.getOwnAccount(userId);
		return DataResponse.success(result);
	}

	/**
	 * Rakip (izlenen) hesap ekler.
	 * Onboarding adım 6 (opsiyonel).
	 */
	@Operation(summary = "Rakip hesap ekle", description = "İzlenecek rakip Instagram hesabı ekler (COMPETITOR_ONLY için).")
	@PostMapping("/monitored/add")
	public DataResponse<MonitoredAccountDto> addMonitoredAccount(
			@Valid @RequestBody AddMonitoredAccountRequest request) {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// İş katmanına devret
		MonitoredAccountDto result = accountService.addMonitoredAccount(userId, request);
		return DataResponse.success(result);
	}

	/**
	 * Kullanıcının izlediği rakip hesapları listeler.
	 */
	@Operation(summary = "Rakip hesapları listele", description = "Kullanıcının izlediği rakip hesapları döndürür.")
	@PostMapping("/monitored/list")
	public DataResponse<List<MonitoredAccountDto>> listMonitoredAccounts() {
		// userId JWT'den al; request body gerekmez
		UUID userId = SecurityUtil.getCurrentUserId();
		List<MonitoredAccountDto> result = accountService.listMonitoredAccounts(userId);
		return DataResponse.success(result);
	}

	/**
	 * Kullanıcının izlediği bir rakip hesabı kaldırır (soft delete).
	 */
	@Operation(summary = "Rakip hesabı kaldır", description = "Belirtilen izlenen rakip hesabı pasifleştirir.")
	@PostMapping("/monitored/remove")
	public DataResponse<Void> removeMonitoredAccount(@Valid @RequestBody RemoveMonitoredAccountRequest request) {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// Soft delete; NOT_FOUND ise ApiException fırlatır
		return accountService.removeMonitoredAccount(userId, request.getUserMonitoredAccountId());
	}
}
