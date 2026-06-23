package com.api.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * POST /payment/wallet yanıtı — kullanıcı cüzdan detayı.
 */
@Getter
@AllArgsConstructor
public class WalletDto {

    // Güncel bakiye
    private BigDecimal balance;

    // Para birimi (TL)
    private String currency;

    // Toplam yüklenen tutar
    private BigDecimal totalTopup;

    // Toplam harcanan tutar
    private BigDecimal totalSpent;
}
