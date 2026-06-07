package com.api.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.UserDto;
import com.api.security.SecurityUtil;
import com.api.service.AuthService;

import lombok.RequiredArgsConstructor;

/**
 * Kullanıcı uçları (korumalı - token gerekir).
 * /user/me: o anki kullanıcının bilgisini döner. userId DAİMA JWT'den (Madde 4).
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

	// Kullanıcı/kimlik iş mantığı
	private final AuthService authService;

	/**
	 * Token'daki kullanıcının profilini döner (istekte userId yok).
	 */
	@PostMapping("/me")
	public DataResponse<UserDto> me() {
		// userId güvenlik bağlamından (JWT) okunur
		UUID userId = SecurityUtil.getCurrentUserId();
		UserDto user = authService.getCurrentUser(userId);
		return DataResponse.success(user);
	}
}
