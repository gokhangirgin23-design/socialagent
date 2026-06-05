package com.api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.SectorDto;
import com.api.dto.SelectSectorRequest;
import com.api.security.SecurityUtil;
import com.api.service.SectorService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Sektör yönetimi uçları (hepsi POST — CLAUDE.md Madde 2).
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 * /sector/list public; /sector/select güvenli (SecurityConfig'te yapılandırılmış).
 */
@RestController
@RequestMapping("/sector")
@RequiredArgsConstructor
public class SectorController {

	// Sektör iş mantığı
	private final SectorService sectorService;

	/**
	 * Aktif tüm sektörleri listeler. Onboarding adım 3 — kimlik doğrulaması gerekmez.
	 */
	@PostMapping("/list")
	public DataResponse<List<SectorDto>> listSectors() {
		// Sektör listesini servis katmanından al
		List<SectorDto> sectors = sectorService.listSectors();
		return DataResponse.success(sectors);
	}

	/**
	 * Kullanıcının sektörünü günceller. Güvenli uç — JWT zorunlu.
	 */
	@PostMapping("/select")
	public DataResponse<Void> selectSector(@Valid @RequestBody SelectSectorRequest request) {
		// userId JWT'den al (CLAUDE.md Madde 4)
		java.util.UUID userId = SecurityUtil.getCurrentUserId();
		// İş katmanına devret
		return sectorService.selectSector(userId, request.getSectorId());
	}
}
