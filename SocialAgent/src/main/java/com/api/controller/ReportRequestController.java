package com.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.AnalysisSelectabilityDto;
import com.api.dto.CreateReportRequestDto;
import com.api.dto.ReportRequestDto;
import com.api.dto.ReportRequestListRequest;
import com.api.security.SecurityUtil;
import com.api.service.ReportRequestService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Rapor isteği yönetimi uçları (hepsi POST — CLAUDE.md Madde 2).
 * Tüm uçlar JWT gerektiren güvenli uçlardır.
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 */
@RestController
@RequestMapping("/report-request")
@RequiredArgsConstructor
public class ReportRequestController {

    // Rapor isteği iş mantığı
    private final ReportRequestService reportRequestService;

    /**
     * Yeni rapor isteği oluşturur ve kuyruğa basar.
     * reportType kullanıcı tarafından açıkça seçilir.
     */
    @PostMapping("/create")
    public DataResponse<ReportRequestDto> createRequest(@Valid @RequestBody CreateReportRequestDto request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        ReportRequestDto result = reportRequestService.createRequest(userId, request);
        return DataResponse.success(result);
    }

    /**
     * Kullanıcının rapor isteklerini sayfalı listeler (en yeni önce).
     */
    @PostMapping("/list")
    public DataResponse<List<ReportRequestDto>> listRequests(
            @RequestBody(required = false) ReportRequestListRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        int page = (request != null) ? request.getPage() : 0;
        int size = (request != null) ? request.getSize() : 10;
        List<ReportRequestDto> result = reportRequestService.listRequests(userId, page, size);
        return DataResponse.success(result);
    }

    /**
     * Kullanıcının hangi analiz türlerini seçebileceğini döndürür (frontend için).
     * Frontend bu bilgiyle seçilemeyen seçenekleri devre dışı bırakır.
     */
    @PostMapping("/available-types")
    public DataResponse<AnalysisSelectabilityDto> availableTypes() {
        UUID userId = SecurityUtil.getCurrentUserId();
        AnalysisSelectabilityDto result = reportRequestService.getAnalysisSelectability(userId);
        return DataResponse.success(result);
    }
}
