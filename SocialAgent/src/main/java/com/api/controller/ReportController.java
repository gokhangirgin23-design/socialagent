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
import com.api.dto.ReportDetailRequest;
import com.api.dto.ReportDto;
import com.api.dto.ReportListRequest;
import com.api.dto.ReportSummaryDto;
import com.api.security.SecurityUtil;
import com.api.service.ReportQueryService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Dashboard rapor uçları (FAZ 8 — CLAUDE.md Bölüm 12).
 * Hepsi POST (CLAUDE.md Madde 2); güvenli uçlar — JWT zorunlu.
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 */
@Tag(name = "Rapor", description = "Üretilmiş raporların görüntülenmesi")
@RestController
@RequestMapping("/report")
@RequiredArgsConstructor
public class ReportController {

	// Dashboard rapor okuma iş mantığı
	private final ReportQueryService reportQueryService;

	/**
	 * Kullanıcının raporlarını sayfalı listeler (en yeni önce; içeriksiz özet).
	 * Endpoint: POST /report/list
	 */
	@Operation(summary = "Raporları listele", description = "Kullanıcının raporlarını sayfalı özet olarak döndürür.")
	@PostMapping("/list")
	public DataResponse<List<ReportSummaryDto>> listReports(
			@RequestBody(required = false) ReportListRequest request) {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// Request null ise varsayılan sayfalama (page=0, size=10)
		int page = (request != null) ? request.getPage() : 0;
		int size = (request != null) ? request.getSize() : 10;
		// İş katmanına devret
		List<ReportSummaryDto> result = reportQueryService.listReports(userId, page, size);
		return DataResponse.success(result);
	}

	/**
	 * Tek raporun detayını (Markdown içerik dahil) döner — yalnızca raporun sahibine.
	 * Rapor yoksa/erişim yoksa NOT_FOUND (data null, HTTP yine 200 — CLAUDE.md Madde 3).
	 * Endpoint: POST /report/detail
	 */
	@Operation(summary = "Rapor detayı", description = "Belirtilen raporun Markdown içeriğini döndürür.")
	@PostMapping("/detail")
	public DataResponse<ReportDto> getReportDetail(@Valid @RequestBody ReportDetailRequest request) {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// Sahiplik korumalı detay (başka kullanıcının raporu null döner)
		ReportDto report = reportQueryService.getReportDetail(userId, request.getReportId());
		if (report == null) {
			// İş anlamında bulunamadı; 404 yerine 005 NOT_FOUND + data null
			return DataResponse.of(ResponseCode.NOT_FOUND);
		}
		return DataResponse.success(report);
	}
}
