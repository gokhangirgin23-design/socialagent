package com.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcının kendi sosyal medya hesabı (user_social_account tablosu).
 * D2 kararı: kullanıcı TEK kendi hesabı seçer.
 * UNIQUE(user_id, platform, account_name) kısıtı şemada + servis katmanında kontrol edilir.
 */
@Entity
@Table(name = "user_social_account")
@Getter
@Setter
public class UserSocialAccount {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "user_social_account_id")
	private UUID userSocialAccountId;

	// Hesap sahibi kullanıcının id'si (nesne referansı yok - CLAUDE.md Madde 6)
	@Column(name = "user_id")
	private UUID userId;

	// Platform adı (örn. "INSTAGRAM")
	@Column(name = "platform")
	private String platform;

	// Hesap kullanıcı adı (örn. "@kullaniciadi")
	@Column(name = "account_name")
	private String accountName;

	// Profil sayfası url'i (opsiyonel)
	@Column(name = "profile_url")
	private String profileUrl;

	// Aktiflik bayrağı (0/1); soft delete için
	@Column(name = "active")
	private Integer active;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
