package com.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Refresh sonucu data payload'u: yeni access token döner.
 */
@Getter
@Setter
public class RefreshResponse {

	// Yeni üretilen JWT access token
	private String accessToken;
	// Access token'ın saniye cinsinden kalan ömrü
	private long accessTokenExpiresInSeconds;
}
