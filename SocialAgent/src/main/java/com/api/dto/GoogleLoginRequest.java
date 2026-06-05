package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Google SSO login isteği gövdesi.
 * Client, Google'dan aldığı id_token'ı gönderir; userId asla istekten okunmaz.
 */
@Getter
@Setter
public class GoogleLoginRequest {

	// Google'dan alınan id_token (zorunlu)
	@NotBlank(message = "idToken zorunludur")
	private String idToken;
}
