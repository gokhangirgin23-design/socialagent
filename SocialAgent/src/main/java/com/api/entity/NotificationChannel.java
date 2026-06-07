package com.api.entity;

/**
 * Bildirimin gönderim kanalı (FAZ 8 revizyonu — CLAUDE.md Bölüm 12).
 * notification.channel kolonunda String olarak saklanır (entity ilişkisiz; CLAUDE.md Madde 6).
 *
 * Bir rapor tamamlanınca her kanal için ayrı bir notification satırı yazılır;
 * her satır kendi success/error_detail bilgisini taşır.
 */
public enum NotificationChannel {

	// E-posta gönderimi (JavaMailSender)
	MAIL,

	// Push bildirimi (FCM/APNs — şimdilik stub)
	PUSH_NOTIFICATION
}
