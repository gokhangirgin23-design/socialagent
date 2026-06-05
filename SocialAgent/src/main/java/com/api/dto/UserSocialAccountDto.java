package com.api.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcının kendi sosyal hesabını istemciye taşıyan DTO.
 * UserSocialAccount entity'sinden MapStruct ile doldurulur.
 */
@Getter
@Setter
public class UserSocialAccountDto {

	// Hesap kaydının benzersiz id'si
	private UUID userSocialAccountId;

	// Hesabın bulunduğu platform (örn. "INSTAGRAM")
	private String platform;

	// Hesap kullanıcı adı
	private String accountName;

	// Profil sayfası url'i (opsiyonel)
	private String profileUrl;
}
