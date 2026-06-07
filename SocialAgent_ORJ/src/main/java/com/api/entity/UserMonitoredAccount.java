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
 * Kullanıcı ile izlenen hesap arasındaki bağlantı tablosu (user_monitored_account).
 * Kullanıcı birden fazla rakip hesabı izleyebilir.
 * UNIQUE(user_id, monitored_account_id) kısıtı şemada + servis katmanında kontrol edilir.
 */
@Entity
@Table(name = "user_monitored_account")
@Getter
@Setter
public class UserMonitoredAccount {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "user_monitored_account_id")
	private UUID userMonitoredAccountId;

	// Bağlantının sahibi kullanıcı (nesne referansı yok - CLAUDE.md Madde 6)
	@Column(name = "user_id")
	private UUID userId;

	// İzlenen hesabın id'si (monitored_account tablosuna bağ)
	@Column(name = "monitored_account_id")
	private UUID monitoredAccountId;

	// Aktiflik bayrağı (0/1); soft delete ile bağlantı kaldırılır
	@Column(name = "active")
	private Integer active;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
