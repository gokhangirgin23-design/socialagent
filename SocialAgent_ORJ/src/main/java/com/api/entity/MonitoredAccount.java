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
 * Global izlenen hesap havuzu (monitored_account tablosu).
 * Aynı hesabı birden fazla kullanıcı izleyebilir; bu tablo tekil hesap kaydını tutar.
 * Kullanıcı bağlantısı user_monitored_account üzerinden kurulur.
 * UNIQUE(platform, account_name) kısıtı şemada + servis katmanında kontrol edilir.
 */
@Entity
@Table(name = "monitored_account")
@Getter
@Setter
public class MonitoredAccount {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "monitored_account_id")
	private UUID monitoredAccountId;

	// Hesabın bulunduğu platform (örn. "INSTAGRAM")
	@Column(name = "platform")
	private String platform;

	// Hesap kullanıcı adı (örn. "rakiphesap")
	@Column(name = "account_name")
	private String accountName;

	// Aktiflik bayrağı (0/1)
	@Column(name = "active")
	private Integer active;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
