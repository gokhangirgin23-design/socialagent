package com.api.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.BalanceCheckRequest;
import com.api.dto.BalanceCheckResponse;
import com.api.dto.PackagesResponse;
import com.api.dto.PurchaseRequest;
import com.api.dto.PurchaseResponse;
import com.api.dto.TransactionsRequest;
import com.api.dto.TransactionsResponse;
import com.api.dto.WalletDto;
import com.api.security.SecurityUtil;
import com.api.service.PaymentService;
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
@Tag(name = "Bakiye", description = "Kullanıcı kredi bakiyesi sorgulama ve paket satın alma")
@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    // Bakiye + kredi maliyeti + ödeme başlatma ReportRequestService üzerinden
    private final ReportRequestService reportRequestService;

    // Hesap hareketleri (transactions) doğrudan PaymentService üzerinden
    private final PaymentService paymentService;

    /**
     * Seçilen rapor tipine göre kredi yeterlilik kontrolü.
     * Frontend, rapor oluşturmadan önce bunu sorgular:
     *  - sufficient=true  → kullanıcı doğrudan /report-request/create'i çağırabilir.
     *  - sufficient=false → kullanıcıya "krediniz yetersiz, paket satın almanız gerekiyor"
     *    uyarısı gösterilir; /payment/packages üzerinden paket satın almaya yönlendirilir.
     */
    @Operation(
            summary = "Kredi yeterlilik kontrolü",
            description = "Seçilen rapor tipi için mevcut kredi bakiyesi ve gereken krediyi döner. "
                    + "sufficient=false ise missingCredits kadar paket satın alınması gerekir.")
    @PostMapping("/balance-check")
    public DataResponse<BalanceCheckResponse> balanceCheck(
            @Valid @RequestBody BalanceCheckRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return DataResponse.success(
                reportRequestService.checkBalance(userId, request.getReportType()));
    }

    /**
     * Kullanıcının hesap hareketlerini (kredi yükleme/harcama/iade) sayfalı listeler.
     */
    @Operation(
            summary = "Hesap hareketleri",
            description = "Kullanıcının kredi hareketlerini (TOPUP/DEBIT/REFUND) en yeni önce sayfalı döndürür; "
                    + "INITIATED/FAILED ödeme denemeleri listeye girmez.")
    @PostMapping("/transactions")
    public DataResponse<TransactionsResponse> transactions(
            @RequestBody(required = false) TransactionsRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        int limit = (request != null && request.getLimit() != null) ? request.getLimit() : 50;
        int offset = (request != null && request.getOffset() != null) ? request.getOffset() : 0;
        return DataResponse.success(paymentService.getTransactionsResponse(userId, limit, offset));
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
     * Satın alınabilir kredi paketlerini + kullanıcının güncel kredi bakiyesini döndürür.
     */
    @Operation(summary = "Kredi paketleri", description = "Satın alınabilir kredi paketlerini ve kullanıcının güncel kredi bakiyesini döndürür.")
    @PostMapping("/packages")
    public DataResponse<PackagesResponse> packages() {
        UUID userId = SecurityUtil.getCurrentUserId();
        return DataResponse.success(reportRequestService.getPackagesResponse(userId));
    }

    /**
     * Kredi paketi satın alma başlatır.
     * PayTR form payload döner; frontend formu PayTR'a POST eder.
     * Ödeme başarılı callback'i /payment/callback'e gelir ve paketin kredisi bakiyeye yansır.
     */
    @Operation(
            summary = "Kredi paketi satın al",
            description = "Seçilen kredi paketi için PayTR ödeme akışını başlatır. "
                    + "PayTR form payload döner; ödeme tamamlanınca /payment/callback tetiklenir ve kredi yüklenir.")
    @PostMapping("/purchase")
    public DataResponse<PurchaseResponse> purchase(
            @Valid @RequestBody PurchaseRequest request, HttpServletRequest httpRequest) {
        UUID userId = SecurityUtil.getCurrentUserId();
        String clientIp = resolveClientIp(httpRequest);
        return DataResponse.success(
                reportRequestService.initiatePurchase(userId, request.getPackageCode(), clientIp));
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
