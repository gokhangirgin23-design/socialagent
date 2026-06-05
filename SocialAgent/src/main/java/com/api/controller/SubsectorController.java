package com.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.SelectSubsectorRequest;
import com.api.dto.SubsectorDto;
import com.api.dto.SubsectorListRequest;
import com.api.security.SecurityUtil;
import com.api.service.SectorService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Alt sektör yönetimi uçları (hepsi POST — CLAUDE.md Madde 2).
 * SectorService inject edilir; ayrı bir SubsectorService gerekmiyor.
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 */
@RestController
@RequestMapping("/subsector")
@RequiredArgsConstructor
public class SubsectorController {

	// Sektör/alt sektör iş mantığı (SectorService'te birlikte yönetilir)
	private final SectorService sectorService;

	/**
	 * Belirtilen sektöre ait aktif alt sektörleri listeler.
	 * Onboarding adım 4 — JWT zorunlu.
	 */
	@PostMapping("/list")
	public DataResponse<List<SubsectorDto>> listSubsectors(@Valid @RequestBody SubsectorListRequest request) {
		// sectorId'yi request'ten al (userId değil; bu parametre filtreleme için)
		List<SubsectorDto> subsectors = sectorService.listSubsectors(request.getSectorId());
		return DataResponse.success(subsectors);
	}

	/**
	 * Kullanıcının alt sektörünü günceller. Güvenli uç — JWT zorunlu.
	 */
	@PostMapping("/select")
	public DataResponse<Void> selectSubsector(@Valid @RequestBody SelectSubsectorRequest request) {
		// userId JWT'den al (CLAUDE.md Madde 4)
		UUID userId = SecurityUtil.getCurrentUserId();
		// İş katmanına devret
		return sectorService.selectSubsector(userId, request.getSubsectorId());
	}
}
