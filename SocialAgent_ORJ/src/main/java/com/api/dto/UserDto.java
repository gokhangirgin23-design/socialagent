package com.api.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcının istemciye dönülen güvenli temsili (hassas alan içermez).
 * UserInfo entity'sinden MapStruct ile üretilir.
 */
@Getter
@Setter
public class UserDto {

	// Kullanıcı id'si
	private UUID userId;
	// E-posta
	private String email;
	// Tam ad
	private String fullName;
	// Profil fotoğrafı
	private String profilePhotoUrl;
	// Seçili sektör (onboarding sonrası dolar)
	private UUID sectorId;
	// Seçili alt sektör (onboarding sonrası dolar)
	private UUID subsectorId;
}
