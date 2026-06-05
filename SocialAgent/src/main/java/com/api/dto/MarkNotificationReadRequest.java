package com.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Bir bildirimi okundu işaretleme isteği (POST /notification/read body).
 * userId JWT'den alınır (CLAUDE.md Madde 4); yalnızca kullanıcının kendi bildirimi işaretlenebilir.
 */
@Getter
@Setter
public class MarkNotificationReadRequest {

	// Okundu işaretlenecek bildirimin id'si (zorunlu)
	@NotNull(message = "notificationId zorunludur")
	private UUID notificationId;
}
