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
 * user_account_dna tablosu entity'si.
 * Hesap bazlı Brand DNA cache; rapora değil kullanıcının bağlı sosyal hesabına bağlıdır.
 * İlişkiler ID kolonu üzerinden; @ManyToOne yok (CLAUDE.md Madde 6).
 */
@Getter
@Setter
@Entity
@Table(name = "user_account_dna")
public class UserAccountDna {

    @Id
    @Column(name = "user_account_dna_id")
    private UUID userAccountDnaId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // user_social_account.user_social_account_id ile bağlantı
    @Column(name = "social_account_id", nullable = false)
    private UUID socialAccountId;

    // AI'dan dönen Brand DNA JSON çıktısı
    @Column(name = "dna_json", columnDefinition = "TEXT", nullable = false)
    private String dnaJson;

    // Analiz edilen gönderi sayısı (<=5)
    @Column(name = "source_post_count", nullable = false)
    private int sourcePostCount = 0;

    @Column(name = "active", nullable = false)
    private short active = 1;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "updated_date")
    private LocalDateTime updatedDate;
}
