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

    // Son güncellenme tarihi
    private LocalDateTime updatedDate;

    // İstek tamamlandıysa oluşturulan raporun id'si (LEFT JOIN report ile gelir; henüz yoksa null)
    private UUID reportId;

    // ===== FAZ CREDIT: kredi kapısı alanları =====
    // Kredi yetersizse true; bu durumda requestId null'dır — kullanıcı önce paket satın almalıdır
    private Boolean insufficientCredits;

    // Yetersizse: rapor için gereken kredi
    private Integer requiredCredits;

    // Yetersizse: kullanıcının mevcut kredi bakiyesi
    private Long creditBalance;
}
