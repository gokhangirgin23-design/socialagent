package com.api.entity;

/**
 * Para hareketinin tipi (user_payment_log.transaction_type — String saklanır).
 * Entity ilişkisi yok (CLAUDE.md Madde 6); enum yalnızca kod tarafında kullanılır.
 */
public enum TransactionType {
    TOPUP,   // PayTR üzerinden bakiye yükleme (+)
    DEBIT,   // rapor isteği için harcama (-)
    REFUND   // iade (+)
}
