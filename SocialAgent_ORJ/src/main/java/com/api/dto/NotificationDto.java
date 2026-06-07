package com.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Bildirimi istemciye taşıyan DTO (FAZ 8 — dashboard / bildirim listesi).
 * Notification entity'sinden MapStruct (NotificationMapper) ile doldurulur.
 */
@Getter
@Setter
public class NotificationDto {

	// Bildirimin benzersiz id'si
	private UUID notificationId;

	// Bildirimin sahibi kullanıcının id'si
	private UUID userId;

	// Kısa başlık
	private String title;

	// Bildirim metni
	private String message;

	// Referans tipi: REPORT | JOB
	private String referenceType;

	// Referans id (rapor ise report_id)
	private UUID referenceId;

	// Okundu bilgisi (0/1)
	private Integer isRead;

	// Oluşturulma tarihi
	private LocalDateTime createdDate;
}
