package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Rakip (izlenen) hesap ekleme isteği (POST /account/monitored/add body).
 * platform ve accountName zorunludur.
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4).
 */
@Getter
@Setter
public class AddMonitoredAccountRequest {

	// Platform adı (zorunlu, örn. "INSTAGRAM")
	@NotBlank(message = "platform zorunludur")
	private String platform;

	// İzlenecek hesabın kullanıcı adı (zorunlu)
	@NotBlank(message = "accountName zorunludur")
	private String accountName;
}
