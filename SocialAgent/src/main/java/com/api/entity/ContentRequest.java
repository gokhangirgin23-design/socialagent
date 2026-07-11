package com.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * content_request tablosu entity'si.
 * İlişkiler ID kolonu üzerinden; @ManyToOne yok (CLAUDE.md Madde 6).
 */
@Getter
@Setter
@Entity
@Table(name = "content_request")
public class ContentRequest {

    @Id
    @Column(name = "content_request_id")
    private UUID contentRequestId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // report.report_id ile bağlantı
    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType;

    @Column(name = "product_image_url")
    private String productImageUrl;

    @Column(name = "include_text_in_visual", nullable = false)
    private boolean includeTextInVisual;

    @Column(name = "edit_instruction")
    private String editInstruction;

    @Column(name = "edit_count", nullable = false)
    private int editCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContentRequestStatus status = ContentRequestStatus.PENDING;

    // OpenAI'dan dönen Brand DNA JSON (yeniden kullanılabilir)
    @Column(name = "brand_dna_json", columnDefinition = "TEXT")
    private String brandDnaJson;

    // JSON array: ["https://s3.../img1.png", ...]
    @Column(name = "visual_urls", columnDefinition = "TEXT")
    private String visualUrls;

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "hashtags", columnDefinition = "TEXT")
    private String hashtags;

    @Column(name = "cta")
    private String cta;

    @Column(name = "first_comment")
    private String firstComment;

    @Column(name = "suggested_post_time")
    private String suggestedPostTime;

    @Column(name = "process_started_date")
    private LocalDateTime processStartedDate;

    @Column(name = "process_finished_date")
    private LocalDateTime processFinishedDate;

    @Column(name = "process_error", columnDefinition = "TEXT")
    private String processError;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "active", nullable = false)
    private short active = 1;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    // ===== Kredi düşümü mutabakatı (reconciliation) — V6 migration =====

    // COMPLETED sonrası kredi başarıyla düşüldü mü? (0/1). İçerik teslim edilse bile bu 0 kalabilir.
    @Column(name = "credit_debited", nullable = false)
    private short creditDebited = 0;

    // Son düşüm denemesinin hatası (başarılıysa null; INSUFFICIENT_CREDITS veya istisna mesajı)
    @Column(name = "credit_debit_error", columnDefinition = "TEXT")
    private String creditDebitError;

    // Düşüm denemesi sayacı (reconciliation poison guard)
    @Column(name = "credit_debit_attempts", nullable = false)
    private int creditDebitAttempts = 0;

    // ===== Ücretsiz ilk kullanım — V11 migration =====

    // Bu içerik ücretsiz ilk kullanım hakkıyla mı oluşturuldu? (0/1)
    @Column(name = "is_free_usage", nullable = false)
    private short isFreeUsage = 0;
}
