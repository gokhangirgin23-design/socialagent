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

    // İşleme durumu: PENDING | PROCESSING | COMPLETED | PARTIAL | FAILED
    private String status;

    // İşleme hata açıklaması (FAILED/PARTIAL'da dolar); başarılıysa null
    private String processError;

    // İşleme başlangıç ve bitiş zamanları
    private LocalDateTime processStartedDate;
    private LocalDateTime processFinishedDate;

    // İsteğin oluşturulma tarihi
    private LocalDateTime createdDate;

    // ===== FAZ PAYMENT: ödeme kapısı alanları =====
    // Bakiye yetersizse true; bu durumda requestId null'dır ve paytr alanı doldurulur
    private Boolean paymentRequired;

    // Tahsil edilecek eksik tutar (deficit), ör. "123.50"
    private String amountToPay;

    // PayTR (ya da local sahte) ödeme formu verisi; frontend bu formu postUrl'e gönderir
    private PaytrFormPayload paytr;
}
