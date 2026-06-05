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
 * Refresh token kaydı (refresh_token tablosu).
 * İlişki yok; user_id sadece ID kolonu olarak tutulur (CLAUDE.md Madde 6).
 */
@Entity
@Table(name = "refresh_token")
@Getter
@Setter
public class RefreshToken {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "refresh_token_id")
	private UUID refreshTokenId;

	// Token'ın sahibi kullanıcı id'si
	@Column(name = "user_id")
	private UUID userId;

	// Opak refresh token değeri (rastgele üretilir)
	@Column(name = "token")
	private String token;

	// Son geçerlilik tarihi
	@Column(name = "expire_date")
	private LocalDateTime expireDate;

	// Aktiflik (0/1) - logout/iptal için 0'a çekilebilir
	@Column(name = "active")
	private Integer active;

	// Oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
