package com.api.entity;

/**
 * PayTR ödeme akışı durumu (user_payment_log.payment_status — String saklanır).
 */
public enum PaymentStatus {
    INITIATED, // STEP 1: token üretildi, ödeme sayfasına yönlendirildi
    PENDING,   // callback bekleniyor / hash doğrulanamadı (retry beklenir)
    SUCCESS,   // callback success + hash doğru → bakiye yüklendi
    FAILED,    // callback failed → bakiye yüklenmedi
    EXPIRED    // request_exp_date geçti
}
