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
import com.api.dto.AnalysisSelectabilityDto;
import com.api.dto.CreateReportRequestDto;
import com.api.dto.ReportRequestDto;
import com.api.dto.ReportRequestListRequest;
import com.api.security.SecurityUtil;
import com.api.service.ReportRequestService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Rapor isteği yönetimi uçları (hepsi POST — CLAUDE.md Madde 2).
 * Tüm uçlar JWT gerektiren güvenli uçlardır.
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 */
@Tag(name = "Rapor İsteği", description = "On-demand rapor talebi ve PayTR bakiye/ödeme kapısı")
@RestController
@RequestMapping("/report-request")
@RequiredArgsConstructor
public class ReportRequestController {

    // Rapor isteği iş mantığı
    private final ReportRequestService reportRequestService;

    /**
     * Yeni rapor isteği oluşturur (FAZ PAYMENT — bakiye kapısı).
     * Bakiye yeterliyse ücret düşülüp istek kuyruğa basılır (data.paymentRequired=false).
     * Yetersizse data.paymentRequired=true + data.paytr ile PayTR ödeme formu döner.
     * reportType kullanıcı tarafından açıkça seçilir.
     */
    @Operation(summary = "Rapor isteği oluştur (ödeme kapılı)", description = "Bakiye yeterliyse ücreti düşüp isteği kuyruğa basar (data.paymentRequired=false); yetersizse eksik tutar için PayTR ödeme formu döner (data.paymentRequired=true, data.paytr).")
    @PostMapping("/create")
    public DataResponse<ReportRequestDto> createRequest(@Valid @RequestBody CreateReportRequestDto request,
            HttpServletRequest httpRequest) {
        UUID userId = SecurityUtil.getCurrentUserId();
        // PayTR STEP 1 token'ı user_ip ister (proxy arkasında X-Forwarded-For)
        String clientIp = resolveClientIp(httpRequest);
        ReportRequestDto result = reportRequestService.createRequest(userId, request, clientIp);
        return DataResponse.success(result);
    }

    /** Dış IP'yi çöz (proxy arkasında X-Forwarded-For; yoksa remote addr). */
    private String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
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
     * Kullanıcının hangi analiz türlerini seçebileceğini döndürür (frontend için).
     * Frontend bu bilgiyle seçilemeyen seçenekleri devre dışı bırakır.
     */
    @Operation(summary = "Seçilebilir analiz tipleri", description = "Kullanıcının hesap durumuna göre seçebileceği analiz tiplerini döndürür.")
    @PostMapping("/available-types")
    public DataResponse<AnalysisSelectabilityDto> availableTypes() {
        UUID userId = SecurityUtil.getCurrentUserId();
        AnalysisSelectabilityDto result = reportRequestService.getAnalysisSelectability(userId);
        return DataResponse.success(result);
    }
}
