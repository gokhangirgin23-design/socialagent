package com.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Kullanıcının hangi analiz türlerini seçebileceğini döndürür (POST /report-request/available-types).
 *
 * Seçilebilirlik kuralları (hesap durumuna göre):
 *   Hiç hesap yok        → sadece NONE (sektör analizi)
 *   Sadece kendi hesap   → NONE veya OWN_ONLY
 *   Sadece rakip hesap   → NONE veya COMPETITOR_ONLY
 *   Kendi + rakip hesap  → NONE, OWN_ONLY veya COMPETITOR_ONLY
 *
 *   noneSelectable: her zaman true; diğerleri hesap varlığına bağlı.
 */
@Getter
@AllArgsConstructor
public class AnalysisSelectabilityDto {

    // Kendi hesabı varsa true → OWN_ONLY seçilebilir
    private boolean ownSelectable;

    // En az 1 rakip hesabı varsa true → COMPETITOR_ONLY seçilebilir
    private boolean competitorSelectable;

    // Sektör analizi hesapsız da yapılabilir, her zaman true
    private final boolean noneSelectable = true;
}
