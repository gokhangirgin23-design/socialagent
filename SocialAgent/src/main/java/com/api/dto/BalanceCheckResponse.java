package com.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Bakiye kontrol yanıtı (POST /payment/balance-check).
 * Frontend bu bilgiyle "krediniz yetersiz" uyarısını ve eksik kredi miktarını gösterir;
 * kullanıcı yetersizse /payment/packages üzerinden paket satın almaya yönlendirilir.
 */
@Getter
@Setter
public class BalanceCheckResponse {

    // Kullanıcının mevcut kredi bakiyesi
    private long creditBalance;

    // Seçilen rapor tipi için gereken kredi
    private int requiredCredits;

    // Kredi yeterli mi?
    private boolean sufficient;

    // Yetersizse eksik kredi (requiredCredits - creditBalance); yeterliyse 0
    private long missingCredits;
}
