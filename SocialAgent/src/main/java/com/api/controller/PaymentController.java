package com.api.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.BalanceCheckRequest;
import com.api.dto.BalanceCheckResponse;
import com.api.security.SecurityUtil;
import com.api.service.ReportRequestService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Kullanıcıya yönelik ödeme/bakiye uçları (hepsi POST — CLAUDE.md Madde 2).
 * Callback ucu (PayTR bildirim) PaymentCallbackController'dadır; bu controller JWT gerektiren uçları barındırır.
 */
@Tag(name = "Bakiye", description = "Kullanıcı bakiye sorgulama")
@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    // Bakiye + fiyat hesabı ReportRequestService'ten (hem paymentService hem priceResolver orada)
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
}
