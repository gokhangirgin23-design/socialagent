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

    // Analiz türü: OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE (istekten gelir)
    @Column(name = "report_type")
    private String reportType;

    // OWN_ONLY / BOTH modunda kullanıcının kendi hesabının id'si; diğerlerinde null
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

    // Aktiflik bayrağı (0/1)
    @Column(name = "active")
    private Integer active;

    // Kaydın oluşturulma tarihi
    @Column(name = "created_date")
    private LocalDateTime createdDate;

    // Kaydın son güncellenme tarihi
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;
}
