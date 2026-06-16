package com.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.common.ResponseCode;
import com.api.dto.MarkNotificationReadRequest;
import com.api.dto.NotificationDto;
import com.api.dto.NotificationListRequest;
import com.api.security.SecurityUtil;
import com.api.service.NotificationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Bildirim uçları (FAZ 8 — CLAUDE.md Bölüm 12).
 * Hepsi POST (CLAUDE.md Madde 2); güvenli uçlar — JWT zorunlu.
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 */
@Tag(name = "Bildirim", description = "Kullanıcı bildirimleri")
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
public class NotificationController {

	// Bildirim iş mantığı
	private final NotificationService notificationService;

	/**
	 * Kullanıcının bildirimlerini sayfalı listeler (en yeni önce). onlyUnread ile filtrelenebilir.
	 * Endpoint: POST /notification/list
	 */
	@Operation(summary = "Bildirimleri listele", description = "Kullanıcının bildirimlerini sayfalı döndürür (en yeni önce).")
	@PostMapping("/list")
	public DataResponse<List<NotificationDto>> listNotifications(
			@RequestBody(required = false) NotificationListRequest request) {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// Request null ise varsayılan sayfalama (page=0, size=10, hepsi)
		int page = (request != null) ? request.getPage() : 0;
		int size = (request != null) ? request.getSize() : 10;
		boolean onlyUnread = (request != null) && request.isOnlyUnread();
		// İş katmanına devret
		List<NotificationDto> result = notificationService.listNotifications(userId, page, size, onlyUnread);
		return DataResponse.success(result);
	}

	/**
	 * Bir bildirimi okundu işaretler (yalnızca kullanıcının kendi bildirimi).
	 * Bildirim bulunamazsa/erişim yoksa NOT_FOUND (data null, HTTP 200 — CLAUDE.md Madde 3).
	 * Endpoint: POST /notification/read
	 */
	@Operation(summary = "Bildirimi okundu işaretle", description = "Belirtilen bildirimi okundu olarak işaretler.")
	@PostMapping("/read")
	public DataResponse<Void> markRead(@Valid @RequestBody MarkNotificationReadRequest request) {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// Ownership korumalı okundu işaretle; 0 -> kullanıcının böyle bildirimi yok
		int updated = notificationService.markAsRead(userId, request.getNotificationId());
		if (updated == 0) {
			return DataResponse.of(ResponseCode.NOT_FOUND);
		}
		// Başarılı; data taşınmaz (null)
		return DataResponse.of(ResponseCode.SUCCESS);
	}

	/**
	 * Kullanıcının okunmamış bildirim sayısını döner (dashboard rozeti).
	 * Endpoint: POST /notification/unread-count
	 */
	@Operation(summary = "Okunmamış bildirim sayısı", description = "Kullanıcının okunmamış bildirim sayısını döndürür.")
	@PostMapping("/unread-count")
	public DataResponse<Long> unreadCount() {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// Okunmamış sayısını al
		long count = notificationService.unreadCount(userId);
		return DataResponse.success(count);
	}
}
