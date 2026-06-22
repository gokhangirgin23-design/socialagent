package com.api.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * POST /report-request/available-types yanıtı.
 * Kullanıcının seçebileceği rapor tiplerini fiyat + cüzdan bakiyesiyle birlikte döndürür.
 * Frontend fiyatı hardcode etmez; bu yanıttan okur.
 */
@Getter
@Builder
@AllArgsConstructor
public class AvailableTypesResponseDto {

    // Para birimi (TL)
    private String currency;

    // Kullanıcının anlık cüzdan bakiyesi
    private BigDecimal balance;

    // Seçilebilir rapor tipleri (hesap durumuna göre filtrelenmiş)
    private List<ReportTypeOption> types;

    /** Tek bir rapor tipi seçeneği (tip, etiket, fiyat). */
    @Getter
    @AllArgsConstructor
    public static class ReportTypeOption {
        private String type;
        private String label;
        private BigDecimal price;
    }
}
