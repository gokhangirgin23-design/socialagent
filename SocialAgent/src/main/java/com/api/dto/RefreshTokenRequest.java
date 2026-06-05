package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Refresh isteği gövdesi: geçerli refresh token ile yeni access token alınır.
 */
@Getter
@Setter
public class RefreshTokenRequest {

	// Daha önce login'de verilen refresh token (zorunlu)
	@NotBlank(message = "refreshToken zorunludur")
	private String refreshToken;
}
