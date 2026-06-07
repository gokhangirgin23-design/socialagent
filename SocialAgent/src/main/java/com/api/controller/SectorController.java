package com.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.SectorDto;
import com.api.dto.SaveSectorRequest;
import com.api.dto.SaveSubsectorRequest;
import com.api.dto.SubsectorDto;
import com.api.dto.SubsectorListRequest;
import com.api.security.SecurityUtil;
import com.api.service.SectorService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Sektör ve alt sektör yönetimi uçları (hepsi POST — CLAUDE.md Madde 2).
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 * /sector/list public; diğerleri güvenli (SecurityConfig'te yapılandırılmış).
 */
@RestController
@RequestMapping("/sector")
@RequiredArgsConstructor
public class SectorController {

	// Sektör ve alt sektör iş mantığı
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
	 * Verilen sektöre ait aktif alt sektörleri listeler. Onboarding adım 4 — JWT zorunlu.
	 */
	@PostMapping("/listSubsectors")
	public DataResponse<List<SubsectorDto>> listSubsectors(@Valid @RequestBody SubsectorListRequest request) {
		// sectorId request'ten alınır; filtreleme parametresidir, userId değildir
		List<SubsectorDto> subsectors = sectorService.listSubsectors(request.getSectorId());
		return DataResponse.success(subsectors);
	}

	/**
	 * Kullanıcının sektörünü kaydeder (alt sektör opsiyonel). Güvenli uç — JWT zorunlu.
	 */
	@PostMapping("/saveSector")
	public DataResponse<Void> saveSector(@Valid @RequestBody SaveSectorRequest request) {
		// userId JWT'den al (CLAUDE.md Madde 4)
		UUID userId = SecurityUtil.getCurrentUserId();
		// İş katmanına devret
		return sectorService.saveSector(userId, request.getSectorId());
	}

	/**
	 * Kullanıcının alt sektörünü kaydeder. Güvenli uç — JWT zorunlu.
	 */
	@PostMapping("/saveSubsector")
	public DataResponse<Void> saveSubsector(@Valid @RequestBody SaveSubsectorRequest request) {
		// userId JWT'den al (CLAUDE.md Madde 4)
		UUID userId = SecurityUtil.getCurrentUserId();
		// İş katmanına devret
		return sectorService.saveSubsector(userId, request.getSubsectorId());
	}
}
