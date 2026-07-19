package com.api.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * POST /payment/wallet yanıtı — kullanıcı cüzdan detayı.
 * FAZ CREDIT: creditBalance eklendi; TL alanları geriye dönük uyumluluk için kalır.
 */
@Getter
@AllArgsConstructor
public class WalletDto {

    // Güncel bakiye (TL — artık güncellenmiyor, geriye dönük uyumluluk için kalır)
    private BigDecimal balance;

    // Para birimi (TL)
    private String currency;

    // Toplam yüklenen tutar (TL)
    private BigDecimal totalTopup;

    // Toplam harcanan tutar (TL)
    private BigDecimal totalSpent;

    // Güncel kredi bakiyesi
    private long creditBalance;
}
