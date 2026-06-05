package com.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Bildirim listeleme isteği + sayfalama parametreleri (POST /notification/list body).
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4).
 */
@Getter
@Setter
public class NotificationListRequest {

	// Sayfa numarası (0'dan başlar); varsayılan 0
	private int page = 0;

	// Sayfa başına kayıt sayısı; varsayılan 10
	private int size = 10;

	// true ise yalnızca okunmamış bildirimler döner; varsayılan false (hepsi)
	private boolean onlyUnread = false;
}
