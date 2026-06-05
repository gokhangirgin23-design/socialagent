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
 * Kullanıcıya gönderilen bildirim (notification tablosu — CLAUDE.md Bölüm 5, 12 — FAZ 8).
 * Rapor tamamlandığında üretilir: reference_type=REPORT, reference_id=report_id, is_read=0.
 *
 * İlişkiler nesne referansı ile değil, yalnızca ID kolonu ile tutulur (CLAUDE.md Madde 6).
 * Tablo FAZ 0'da V1__init_schema.sql ile oluşturulmuştu; bu fazda yalnızca kod katmanı eklenir.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
public class Notification {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "notification_id")
	private UUID notificationId;

	// Bildirimin sahibi kullanıcı (nesne referansı yok - CLAUDE.md Madde 6)
	@Column(name = "user_id")
	private UUID userId;

	// Kısa başlık (örn. "Raporunuz hazır")
	@Column(name = "title")
	private String title;

	// Bildirim metni (örn. "BOTH analiz raporunuz oluşturuldu.")
	@Column(name = "message")
	private String message;

	// Referans tipi: REPORT | JOB (ReferenceType enum)
	@Column(name = "reference_type")
	private String referenceType;

	// Referans id (REPORT için report_id, JOB için user_job_id)
	@Column(name = "reference_id")
	private UUID referenceId;

	// Okundu bilgisi (0 = okunmadı, 1 = okundu)
	@Column(name = "is_read")
	private Integer isRead;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi (okundu işaretlenince güncellenir)
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
