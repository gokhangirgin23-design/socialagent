package com.api.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.DashboardSummaryDto;
import com.api.security.SecurityUtil;
import com.api.service.DashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Dashboard özet ucu — tek POST ile tüm dashboard verisini döndürür (CLAUDE.md Madde 2).
 * userId daima JWT'den; istekten okunmaz (CLAUDE.md Madde 4).
 */
@Tag(name = "Dashboard", description = "Dashboard özet verisi")
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Dashboard'ı tek çağrıda besler: skor, izlenen sayısı, son analiz,
     * son rapor, latest insight, uyarılar ve cüzdan.
     */
    @Operation(
            summary = "Dashboard özeti",
            description = "Hesap skoru, izlenen rakip sayısı, son rapor durumu, structured insight "
                    + "ve cüzdan bilgisini tek çağrıda döndürür.")
    @PostMapping("/summary")
    public DataResponse<DashboardSummaryDto> summary() {
        UUID userId = SecurityUtil.getCurrentUserId();
        return DataResponse.success(dashboardService.buildSummary(userId));
    }
}
