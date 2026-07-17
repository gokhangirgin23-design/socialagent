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
import com.api.dto.AvailableTypesResponseDto;
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
@Tag(name = "Rapor İsteği", description = "On-demand rapor talebi ve kredi kapısı")
@RestController
@RequestMapping("/report-request")
@RequiredArgsConstructor
public class ReportRequestController {

    // Rapor isteği iş mantığı
    private final ReportRequestService reportRequestService;

    /**
     * Yeni rapor isteği oluşturur (FAZ CREDIT — kredi kapısı).
     * Kredi yeterliyse istek kuyruğa basılır (data.insufficientCredits=false).
     * Yetersizse data.insufficientCredits=true + data.requiredCredits + data.creditBalance döner;
     * kullanıcı /payment/packages üzerinden paket satın almaya yönlendirilir.
     * Geliştirme 2: reportType artık body'den okunmaz; mod (BOTH/OWN_ONLY) hesap/rakip durumuna
     * göre backend'de otomatik belirlenir (bkz. ReportRequestService.createRequest).
     */
    @Operation(summary = "Rapor isteği oluştur (kredi kapılı)", description = "Kredi yeterliyse isteği kuyruğa basar (data.insufficientCredits=false); yetersizse data.insufficientCredits=true + data.requiredCredits + data.creditBalance döner.")
    @PostMapping("/create")
    public DataResponse<ReportRequestDto> createRequest(@Valid @RequestBody CreateReportRequestDto request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        ReportRequestDto result = reportRequestService.createRequest(userId, request);
        return DataResponse.success(result);
    }

    /**
     * Kullanıcının rapor isteklerini sayfalı listeler (en yeni önce).
     */
    @Operation(summary = "Rapor isteklerini listele", description = "Kullanıcının rapor isteklerini sayfalı döndürür (en yeni önce).")
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
     * Rapor oluşturmanın kredi maliyeti, ücretsiz hak durumu, bakiye ve oluşturulabilirlik
     * (canCreate/blockReason) bilgisini tek nesnede döndürür (frontend için).
     * Geliştirme 2: UI'da tip seçimi yok; frontend bu bilgiyle tek "Raporu oluştur" butonunu
     * gösterir/gizler ve engelliyse blockReason'ı kullanıcıya gösterir.
     */
    @Operation(summary = "Rapor oluşturma durumu", description = "Kullanıcının rapor oluşturup oluşturamayacağını, kredi maliyetini, ücretsiz hakkı ve bakiyesini döndürür.")
    @PostMapping("/available-types")
    public DataResponse<AvailableTypesResponseDto> availableTypes() {
        UUID userId = SecurityUtil.getCurrentUserId();
        AvailableTypesResponseDto result = reportRequestService.getAnalysisSelectability(userId);
        return DataResponse.success(result);
    }
}
