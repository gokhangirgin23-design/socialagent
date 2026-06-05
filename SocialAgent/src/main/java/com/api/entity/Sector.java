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
 * Sektör tablosu (sector).
 * Kullanıcı onboarding'de bir sektör seçer; onboarding adım 3.
 * İlişki yok; foreign key sadece ID olarak tutulur (CLAUDE.md Madde 6).
 */
@Entity
@Table(name = "sector")
@Getter
@Setter
public class Sector {

	// Birincil anahtar (UUID, seed verisi ile sabit)
	@Id
	@Column(name = "sector_id")
	private UUID sectorId;

	// Sektörün görünen adı (örn. "Teknoloji")
	@Column(name = "name")
	private String name;

	// Aktiflik bayrağı (0/1); silinmiş sektörler 0
	@Column(name = "active")
	private Integer active;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
