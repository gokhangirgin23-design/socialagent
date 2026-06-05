package com.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Login sonucu data payload'u (DataResponse<LoginResponse> içinde döner).
 * Access (JWT) + refresh (opak) token ve kullanıcı bilgisini taşır.
 */
@Getter
@Setter
public class LoginResponse {

	// Kısa ömürlü JWT access token
	private String accessToken;
	// Uzun ömürlü opak refresh token
	private String refreshToken;
	// Access token'ın saniye cinsinden kalan ömrü
	private long accessTokenExpiresInSeconds;
	// Giriş yapan kullanıcı bilgisi
	private UserDto user;
}
