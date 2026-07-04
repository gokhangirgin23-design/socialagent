package com.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * Hesap Hareketleri satırı (POST /payment/transactions).
 */
@Getter
@Setter
public class TransactionDto {

    // TOPUP | DEBIT | REFUND
    private String transactionType;

    // TOPUP'ta yüklenen, DEBIT/REFUND'da harcanan/iade edilen kredi
    private Long creditAmount;

    // TOPUP'ta paketin TL tutarı; DEBIT/REFUND'da null
    private BigDecimal tlAmount;

    // TOPUP'ta katalogdan çözülen paket adı; diğerlerinde null
    private String packageName;

    // DEBIT/REFUND'da Türkçe ürün etiketi (ör. "Rapor Oluşturma"); TOPUP'ta null
    private String productLabel;

    private LocalDateTime createdDate;
}
