package com.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Rapor isteği (report_request tablosu).
 * Kullanıcı isteği oluşturulunca direkt kuyruğa basılır (scheduler yok).
 * report_type istekten gelir; tablo doluluk durumu değil kullanıcı tercihi esas alınır.
 */
@Entity
@Table(name = "report_request")
@Getter
@Setter
public class ReportRequest {

    // Birincil anahtar (UUID, kod tarafında üretilir)
    @Id
    @Column(name = "request_id")
    private UUID requestId;

    // İsteği oluşturan kullanıcı (nesne referansı yok - CLAUDE.md Madde 6)
    @Column(name = "user_id")
    private UUID userId;

    // Analiz türü: OWN_ONLY | NONE (rakip hesap özelliğinin kaldırılmasıyla COMPETITOR_ONLY/BOTH silindi)
    @Column(name = "report_type")
    private String reportType;

    // OWN_ONLY modunda kullanıcının kendi hesabının id'si; diğerlerinde null
    @Column(name = "selected_user_social_account_id")
    private UUID selectedUserSocialAccountId;

    // Kuyruğa basıldı mı? (0/1)
    @Column(name = "queue_pushed")
    private Integer queuePushed;

    // Kuyruğa basılma zamanı; nullable
    @Column(name = "queue_push_date")
    private LocalDateTime queuePushDate;

    // Kuyruğa basma hatası; başarılıysa null
    @Column(name = "queue_error")
    private String queueError;

    // ===== İş (job) yaşam döngüsü — V2 migration =====

    // İşleme durumu: PENDING | PROCESSING | COMPLETED | PARTIAL | FAILED (bkz. ReportRequestStatus)
    @Column(name = "status")
    private String status;

    // İşleme başlangıç zamanı (PROCESSING'e geçişte set); nullable
    @Column(name = "process_started_date")
    private LocalDateTime processStartedDate;

    // İşleme bitiş zamanı (terminal duruma geçişte set); nullable
    @Column(name = "process_finished_date")
    private LocalDateTime processFinishedDate;

    // İşleme hatası açıklaması (FAILED/PARTIAL'da dolar); başarılıysa null
    @Column(name = "process_error")
    private String processError;

    // Requeue/sweep deneme sayacı (poison-message koruması)
    @Column(name = "attempt_count")
    private Integer attemptCount;

    // Aktiflik bayrağı (0/1)
    @Column(name = "active")
    private Integer active;

    // Kaydın oluşturulma tarihi
    @Column(name = "created_date")
    private LocalDateTime createdDate;

    // Kaydın son güncellenme tarihi
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    // ===== Kredi düşümü mutabakatı (reconciliation) — V6 migration =====

    // COMPLETED sonrası kredi başarıyla düşüldü mü? (0/1). Rapor teslim edilse bile bu 0 kalabilir.
    // Java-side default ŞART: kolon NOT NULL, ama yeni ReportRequest() üreten servisler (ör.
    // ReportRequestService.persistAndQueue) bu alanı hiç set etmiyor — set edilmezse Hibernate
    // INSERT'e açıkça NULL yazar (DB DEFAULT yalnızca kolon INSERT listesinden TAMAMEN
    // çıkarılırsa devreye girer) ve NOT NULL ihlali oluşur (üretim ortamında yaşandı).
    @Column(name = "credit_debited")
    private Integer creditDebited = 0;

    // Son düşüm denemesinin hatası (başarılıysa null; INSUFFICIENT_CREDITS veya istisna mesajı)
    @Column(name = "credit_debit_error")
    private String creditDebitError;

    // Düşüm denemesi sayacı (reconciliation poison guard) — aynı NULL-INSERT riski için varsayılan
    @Column(name = "credit_debit_attempts")
    private Integer creditDebitAttempts = 0;

    // ===== Çift-tık/duplicate koruması (E7 fix) — V7 migration =====

    // Yalnızca PENDING/PROCESSING iken userId taşır (terminal durumda NULL'a döner).
    // UNIQUE(active_lock_key) — NULL'lar kısıttan muaf olduğundan "kullanıcı başına en fazla
    // 1 aktif rapor isteği" kuralını DB seviyesinde, eşzamanlı istekler arasında bile uygular.
    @Column(name = "active_lock_key")
    private UUID activeLockKey;

    // ===== Rapor listesinde sektör/hesap gösterimi — V10 migration =====

    // Rapor OLUŞTURULDUĞU ANDAKİ kullanıcı sektörü/alt sektörü (canlı user_info'ya değil, bu
    // ANLIK değere göre gösterilir — kullanıcı sonradan sektör değiştirirse eski raporlar
    // yanlış görünmesin diye). Bu migration'dan önceki raporlarda null.
    @Column(name = "sector_id")
    private UUID sectorId;

    @Column(name = "subsector_id")
    private UUID subsectorId;

    // ===== Ücretsiz ilk kullanım — V11 migration =====

    // Bu rapor ücretsiz ilk kullanım hakkıyla mı oluşturuldu? (0/1). Java-side default ŞART
    // (bkz. credit_debited yorumu yukarıda — aynı NOT NULL/Hibernate-NULL-INSERT riski).
    @Column(name = "is_free_usage")
    private Integer isFreeUsage = 0;

    // ===== Rapor listesinde kendi hesap adının dondurulması — V12 migration =====

    // Rapor OLUŞTURULDUĞU ANDAKİ kendi hesap adı (canlı user_social_account'a değil, bu ANLIK
    // değere göre gösterilir — kullanıcı sonradan hesap adını değiştirirse (AccountService
    // yerinde günceller, INSERT yapmaz) eski raporlar yanlış görünmesin diye). Yalnızca
    // OWN_ONLY modunda dolar; bu migration'dan önceki raporlarda null.
    @Column(name = "own_account_name")
    private String ownAccountName;
}
