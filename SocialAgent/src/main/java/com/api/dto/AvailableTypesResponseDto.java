package com.api.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * POST /report-request/available-types yanıtı.
 * Kullanıcının seçebileceği rapor tiplerini kredi maliyeti + kredi bakiyesiyle birlikte döndürür.
 * Frontend kredi maliyetini hardcode etmez; bu yanıttan okur.
 */
@Getter
@Builder
@AllArgsConstructor
public class AvailableTypesResponseDto {

    // Kullanıcının anlık kredi bakiyesi
    private long creditBalance;

    // Seçilebilir rapor tipleri (hesap durumuna göre filtrelenmiş)
    private List<ReportTypeOption> types;

    /** Tek bir rapor tipi seçeneği (tip, etiket, kredi maliyeti, ücretsiz hak durumu). */
    @Getter
    @AllArgsConstructor
    public static class ReportTypeOption {
        private String type;
        private String label;
        private int creditCost;
        // V11: kullanıcının hâlâ ücretsiz ilk rapor hakkı varsa true (tip fark etmeksizin — fiyat zaten sabit)
        private boolean free;
    }
}
