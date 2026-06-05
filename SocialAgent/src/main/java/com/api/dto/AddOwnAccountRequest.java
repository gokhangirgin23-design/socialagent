package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcının kendi sosyal hesabını ekleme isteği (POST /account/own/add body).
 * platform ve accountName zorunludur; profileUrl opsiyoneldir.
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4).
 */
@Getter
@Setter
public class AddOwnAccountRequest {

	// Platform adı (zorunlu, örn. "INSTAGRAM")
	@NotBlank(message = "platform zorunludur")
	private String platform;

	// Hesap kullanıcı adı (zorunlu)
	@NotBlank(message = "accountName zorunludur")
	private String accountName;

	// Profil sayfası url'i (opsiyonel)
	private String profileUrl;
}
