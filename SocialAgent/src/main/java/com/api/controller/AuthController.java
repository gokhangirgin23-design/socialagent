package com.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.GoogleLoginRequest;
import com.api.dto.LoginResponse;
import com.api.dto.RefreshResponse;
import com.api.dto.RefreshTokenRequest;
import com.api.service.AuthService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Kimlik doğrulama uçları (hepsi POST, public - SecurityConfig'te /auth/** açık).
 * userId istekten ALINMAZ; login id_token'dan, sonraki uçlar JWT'den.
 */
@Tag(name = "Kimlik Doğrulama", description = "Google ile giriş ve JWT token yenileme")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	// Kimlik doğrulama iş mantığı
	private final AuthService authService;

	/**
	 * Google SSO login/register. id_token doğrulanır, access+refresh döner.
	 */
	@Operation(summary = "Google ile giriş", description = "Google id_token doğrular; access ve refresh token üretir.")
	@PostMapping("/google")
	public DataResponse<LoginResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
		// İş katmanına devret, başarılı sonucu sar
		LoginResponse result = authService.googleLogin(request.getIdToken());
		return DataResponse.success(result);
	}

	/**
	 * Refresh token ile yeni access token üretir.
	 */
	@Operation(summary = "Token yenile", description = "Geçerli refresh token ile yeni access token üretir.")
	@PostMapping("/refresh")
	public DataResponse<RefreshResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
		RefreshResponse result = authService.refreshAccessToken(request.getRefreshToken());
		return DataResponse.success(result);
	}
}
