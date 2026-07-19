package com.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * POST /report-request/available-types yanıtı.
 * Geliştirme 2: UI'da tip seçimi kalktığından tek bir rapor "oluşturma durumu" nesnesi döner —
 * kredi maliyeti, ücretsiz hak, bakiye ve oluşturulabilirlik (canCreate/blockReason).
 */
@Getter
@Builder
@AllArgsConstructor
public class AvailableTypesResponseDto {

    // Rapor oluşturmanın kredi maliyeti (tüm modlarda tek fiyat)
    private int creditCost;

    // V11: kullanıcının hâlâ ücretsiz ilk rapor hakkı varsa true
    private boolean free;

    // Kullanıcının anlık kredi bakiyesi
    private long creditBalance;

    // Kullanıcı şu an rapor oluşturabilir mi? (kendi hesabı + gerektiğinde sektör şartı)
    private boolean canCreate;

    // canCreate=false ise kullanıcıya gösterilecek sebep metni; aksi halde null
    private String blockReason;
}
