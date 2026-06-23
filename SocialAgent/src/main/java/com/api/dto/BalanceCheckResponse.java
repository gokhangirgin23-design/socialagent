package com.api.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

/**
 * Bakiye kontrol yanıtı (POST /payment/balance-check).
 * Frontend bu bilgiyle "bakiyeniz yetersiz" uyarısını ve eksik tutarı gösterir;
 * kullanıcı yüklemek istediği miktarı topupAmount olarak /report-request/create'e iletir.
 */
@Getter
@Setter
public class BalanceCheckResponse {

    // Kullanıcının mevcut bakiyesi (TL)
    private BigDecimal balance;

    // Seçilen rapor tipi için gerekli ücret (TL)
    private BigDecimal price;

    // Bakiye yeterli mi?
    private boolean sufficient;

    // Yetersizse minimum yüklenmesi gereken tutar (price - balance); yeterliyse 0
    private BigDecimal deficit;

    // Para birimi (TL)
    private String currency;
}
