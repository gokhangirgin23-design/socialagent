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

    // Analiz türü: OWN_ONLY | COMPETITOR_ONLY | NONE (istekten gelir; BOTH kaldırıldı)
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
    @Column(name = "credit_debited")
    private Integer creditDebited;

    // Son düşüm denemesinin hatası (başarılıysa null; INSUFFICIENT_CREDITS veya istisna mesajı)
    @Column(name = "credit_debit_error")
    private String creditDebitError;

    // Düşüm denemesi sayacı (reconciliation poison guard)
    @Column(name = "credit_debit_attempts")
    private Integer creditDebitAttempts;

    // ===== Çift-tık/duplicate koruması (E7 fix) — V7 migration =====

    // Yalnızca PENDING/PROCESSING iken userId taşır (terminal durumda NULL'a döner).
    // UNIQUE(active_lock_key) — NULL'lar kısıttan muaf olduğundan "kullanıcı başına en fazla
    // 1 aktif rapor isteği" kuralını DB seviyesinde, eşzamanlı istekler arasında bile uygular.
    @Column(name = "active_lock_key")
    private UUID activeLockKey;
}
