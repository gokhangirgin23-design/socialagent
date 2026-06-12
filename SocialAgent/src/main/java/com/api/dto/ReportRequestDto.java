package com.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Rapor isteği bilgisini istemciye taşıyan DTO.
 * ReportRequest entity'sinden MapStruct ile doldurulur.
 */
@Getter
@Setter
public class ReportRequestDto {

    // İsteğin benzersiz id'si
    private UUID requestId;

    // İsteği oluşturan kullanıcının id'si
    private UUID userId;

    // Analiz türü: OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE
    private String reportType;

    // Kuyruğa basıldı mı? (0/1)
    private Integer queuePushed;

    // Kuyruğa basılma zamanı
    private LocalDateTime queuePushDate;

    // Kuyruğa basma hatası (başarılıysa null)
    private String queueError;

    // İsteğin oluşturulma tarihi
    private LocalDateTime createdDate;
}
