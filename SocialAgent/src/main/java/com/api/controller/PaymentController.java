package com.api.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.BalanceCheckRequest;
import com.api.dto.BalanceCheckResponse;
import com.api.dto.TopupRequest;
import com.api.dto.TopupResponse;
import com.api.dto.WalletDto;
import com.api.security.SecurityUtil;
import com.api.service.ReportRequestService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Kullanıcıya yönelik ödeme/bakiye uçları (hepsi POST — CLAUDE.md Madde 2).
 * Callback ucu (PayTR bildirim) PaymentCallbackController'dadır; bu controller JWT gerektiren uçları barındırır.
 */
@Tag(name = "Bakiye", description = "Kullanıcı bakiye sorgulama ve yükleme")
@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    // Bakiye + fiyat + ödeme başlatma ReportRequestService üzerinden
    private final ReportRequestService reportRequestService;

    /**
     * Seçilen rapor tipine göre bakiye yeterlilik kontrolü.
     * Frontend, rapor oluşturmadan önce bunu sorgular:
     *  - sufficient=true  → kullanıcı doğrudan /report-request/create'i çağırabilir.
     *  - sufficient=false → kullanıcıya "bakiyeniz yetersiz, ödeme sayfasına yönlendirileceksiniz"
     *    uyarısı gösterilir; topupAmount seçtirilerek /report-request/create'e iletilir.
     */
    @Operation(
            summary = "Bakiye yeterlilik kontrolü",
            description = "Seçilen rapor tipi için mevcut bakiye ve gereken ücreti döner. "
                    + "sufficient=false ise deficit kadar (ya da daha fazla) yükleme gerekir.")
    @PostMapping("/balance-check")
    public DataResponse<BalanceCheckResponse> balanceCheck(
            @Valid @RequestBody BalanceCheckRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return DataResponse.success(
                reportRequestService.checkBalance(userId, request.getReportType()));
    }

    /**
     * Kullanıcının güncel cüzdan bilgisini döndürür (bakiye, para birimi, toplam yükleme/harcama).
     * LocalPaytrController'daki /local/payment/wallet ucunun prod karşılığıdır.
     */
    @Operation(summary = "Cüzdan bilgisi", description = "Kullanıcının güncel bakiyesini ve hareket özetini döndürür.")
    @PostMapping("/wallet")
    public DataResponse<WalletDto> wallet() {
        UUID userId = SecurityUtil.getCurrentUserId();
        return DataResponse.success(reportRequestService.getWalletDto(userId));
    }

    /**
     * Rapordan bağımsız bakiye yükleme (standalone topup).
     * PayTR form payload döner; frontend formu PayTR'a POST eder.
     * Ödeme başarılı callback'i /payment/callback'e gelir ve bakiyeye yansır.
     */
    @Operation(
            summary = "Bakiye yükle (standalone)",
            description = "Rapor isteği olmaksızın bakiye yükleme başlatır. "
                    + "PayTR form payload döner; ödeme tamamlanınca /payment/callback tetiklenir.")
    @PostMapping("/topup")
    public DataResponse<TopupResponse> topup(
            @Valid @RequestBody TopupRequest request, HttpServletRequest httpRequest) {
        UUID userId = SecurityUtil.getCurrentUserId();
        String clientIp = resolveClientIp(httpRequest);
        return DataResponse.success(
                reportRequestService.initiateTopup(userId, request.getAmount(), clientIp));
    }

    /** Dış IP'yi çöz (proxy arkasında X-Forwarded-For; yoksa remote addr). */
    private String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
