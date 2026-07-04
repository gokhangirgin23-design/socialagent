package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Yeni rapor isteği oluşturma body'si (POST /report-request/create).
 * reportType kullanıcı tarafından açıkça seçilir; hesap doluluk durumuna göre otomatik belirlenmez.
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4).
 */
@Getter
@Setter
public class CreateReportRequestDto {

    // Analiz türü: OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE (zorunlu, kullanıcı seçer)
    @NotBlank(message = "reportType zorunludur")
    private String reportType;
}
