package com.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Yeni rapor isteği oluşturma body'si (POST /report-request/create).
 * Geliştirme 2: reportType artık kullanıcıdan alınmaz — hesap/rakip durumuna göre backend
 * otomatik belirler (bkz. ReportRequestService.createRequest). Alan yalnızca eski istemcilerle
 * geriye uyumluluk için DTO'da bırakıldı; gönderilse de OKUNMAZ.
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4).
 */
@Getter
@Setter
public class CreateReportRequestDto {

    // ARTIK KULLANILMIYOR — geriye uyumluluk için DTO'da duruyor, backend bu alanı okumaz.
    private String reportType;
}
