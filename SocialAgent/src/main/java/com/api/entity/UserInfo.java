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
 * Kullanıcı bilgisi (user_info tablosu).
 * İlişki yok; foreign key'ler sadece ID kolonu olarak tutulur (CLAUDE.md Madde 6).
 */
@Entity
@Table(name = "user_info")
@Getter
@Setter
public class UserInfo {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "user_id")
	private UUID userId;

	// Google hesabının benzersiz id'si (subject) - UNIQUE
	@Column(name = "google_id")
	private String googleId;

	// E-posta - UNIQUE
	@Column(name = "email")
	private String email;

	// Tam ad
	@Column(name = "full_name")
	private String fullName;

	// Profil fotoğrafı url'i
	@Column(name = "profile_photo_url")
	private String profilePhotoUrl;

	// Seçilen sektör id'si (FAZ 2'de set edilir)
	@Column(name = "sector_id")
	private UUID sectorId;

	// Seçilen alt sektör id'si (FAZ 2'de set edilir)
	@Column(name = "subsector_id")
	private UUID subsectorId;

	// Aktiflik (0/1)
	@Column(name = "active")
	private Integer active;

	// Oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
