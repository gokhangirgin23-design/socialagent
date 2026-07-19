package com.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.api.service.PaymentService;
import com.api.service.PaytrGateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PayTR STEP 2 — bildirim (callback) ucu (FAZ CREDIT).
 *
 * ÖNEMLİ İSTİSNALAR (bilinçli — PayTR protokolü gerektiriyor):
 *  - Bu uç BaseResponse DEĞİL, düz metin "OK" döner. PayTR "OK" almazsa işlemi tamamlanmadı
 *    sayar ve dakikada bir TEKRAR dener (idempotensi şart).
 *  - SecurityConfig'te /payment/callback permitAll (PayTR JWT göndermez). CSRF zaten kapalı.
 *  - Form (application/x-www-form-urlencoded) olarak gelir.
 *
 * Akış:
 *  1) Hash doğrula (gateway.verifyCallback). GEÇERSİZ → "OK" DÖNME (PayTR retry'lasın;
 *     genelde config/kod bug'ıdır, para askıda kalır, kayıp olmaz).
 *  2) applyCallback → idempotent (merchant_oid + processed). success ise paketin kredisi yüklenir.
 *  3) Düz "OK" dön.
 */
@Slf4j
@Tag(name = "Ödeme", description = "PayTR Direkt API ödeme bildirimi")
@RestController
@RequiredArgsConstructor
public class PaymentCallbackController {

    private final PaytrGateway paytrGateway;
    private final PaymentService paymentService;

    @Operation(summary = "PayTR bildirim (callback)", description = "PayTR'ın server-to-server ödeme bildirimini idempotent işler; başarılıysa satın alınan paketin kredisini yükler. Düz 'OK' döner; JWT gerektirmez (PayTR protokolü).")
    @PostMapping("/payment/callback")
    public String callback(
            @RequestParam(value = "merchant_oid", required = false) String merchantOid,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "total_amount", required = false) String totalAmount,
            @RequestParam(value = "hash", required = false) String hash,
            @RequestParam(value = "failed_reason_code", required = false) String failedCode,
            @RequestParam(value = "failed_reason_msg", required = false) String failedMsg,
            @RequestParam(value = "payment_type", required = false) String paymentType,
            @RequestParam(value = "test_mode", required = false) String testMode) {

        // 1) Hash doğrula — geçersizse "OK" DÖNME (PayTR retry'lasın)
        boolean valid = paytrGateway.verifyCallback(merchantOid, status, totalAmount, hash);
        if (!valid) {
            log.warn("PayTR callback hash geçersiz: merchant_oid={}", merchantOid);
            return "PAYTR notification failed: bad hash";
        }

        boolean isSuccess = "success".equalsIgnoreCase(status);
        String raw = "merchant_oid=" + merchantOid + "&status=" + status + "&total_amount=" + totalAmount;

        // 2) İdempotent uygula (kredi yükleme dahil)
        paymentService.applyCallback(
                merchantOid, isSuccess, totalAmount, paymentType, failedCode, failedMsg, testMode, raw);

        // 3) Her durumda düz "OK"
        return "OK";
    }
}
