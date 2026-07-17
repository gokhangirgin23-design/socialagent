package com.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.AddOwnAccountRequest;
import com.api.dto.UserSocialAccountDto;
import com.api.security.SecurityUtil;
import com.api.service.AccountService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Hesap yönetimi uçları: kullanıcının kendi hesabı (hepsi POST — CLAUDE.md Madde 2).
 * Tüm uçlar JWT gerektiren güvenli uçlardır.
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 */
@Tag(name = "Hesap", description = "Kullanıcının kendi sosyal hesap yönetimi")
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
	@Operation(summary = "Kendi hesabı ekle", description = "Kullanıcının kendi Instagram hesabını ekler (OWN_ONLY analizleri için).")
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
	 * Kullanıcının aktif kendi hesabını kaldırır (soft delete).
	 */
	@Operation(summary = "Kendi hesabı kaldır", description = "Kullanıcının ekli kendi hesabını pasifleştirir.")
	@PostMapping("/own/remove")
	public DataResponse<Void> removeOwnAccount() {
		UUID userId = SecurityUtil.getCurrentUserId();
		return accountService.removeOwnAccount(userId);
	}
}
