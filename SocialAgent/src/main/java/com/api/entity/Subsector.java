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
 * Alt sektör tablosu (subsector).
 * Her alt sektör bir sektöre bağlıdır (sector_id ile, @ManyToOne YOK).
 * UNIQUE(sector_id, name) kısıtı şemada tanımlı; servis katmanında da kontrol edilir.
 */
@Entity
@Table(name = "subsector")
@Getter
@Setter
public class Subsector {

	// Birincil anahtar (UUID, seed verisi ile sabit)
	@Id
	@Column(name = "subsector_id")
	private UUID subsectorId;

	// Bağlı sektörün id'si (nesne referansı yok - CLAUDE.md Madde 6)
	@Column(name = "sector_id")
	private UUID sectorId;

	// Alt sektörün görünen adı (örn. "Mobil Uygulama")
	@Column(name = "name")
	private String name;

	// Aktiflik bayrağı (0/1)
	@Column(name = "active")
	private Integer active;

	// Bağlı sektör içindeki gösterim sırası (küçük önce) — V13 migration
	@Column(name = "display_order")
	private Integer displayOrder;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
