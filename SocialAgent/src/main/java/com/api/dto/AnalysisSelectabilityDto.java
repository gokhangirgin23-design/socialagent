package com.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Kullanıcının hangi analiz türlerini seçebileceğini döndürür (POST /report-request/available-types).
 * Frontend bu bilgiyle seçilemeyen seçenekleri devre dışı bırakır.
 *
 * Kurallar:
 *   OWN_SELECTABLE       : kullanıcının aktif kendi hesabı varsa true
 *   COMPETITOR_SELECTABLE: kullanıcının en az bir izlediği rakip hesabı varsa true
 *   BOTH_SELECTABLE      : hem kendi hem rakip hesabı varsa true
 *   NONE_SELECTABLE      : her zaman true (sektör araştırması için hesap gerekmez)
 */
@Getter
@AllArgsConstructor
public class AnalysisSelectabilityDto {

    // Kendi hesap analizi seçilebilir mi?
    private boolean ownSelectable;

    // Rakip hesap analizi seçilebilir mi?
    private boolean competitorSelectable;

    // Her ikisi birlikte seçilebilir mi?
    private boolean bothSelectable;

    // Sektör araştırması her zaman seçilebilir
    private final boolean noneSelectable = true;
}
