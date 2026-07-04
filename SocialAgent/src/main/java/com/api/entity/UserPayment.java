package com.api.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcı cüzdanı / bakiye hesabı (user_payment tablosu — FAZ PAYMENT).
 * Kullanıcı başına TEK satır; bakiye kontrolü bu satır üzerinden yapılır.
 *
 * İlişkiler nesne referansı ile değil yalnızca ID kolonu ile tutulur (CLAUDE.md Madde 6).
 * UUID kod tarafında üretilir. Insert JPA saveAndFlush; bakiye güncellemesi JdbcTemplate native.
 */
@Entity
@Table(name = "user_payment")
@Getter
@Setter
public class UserPayment {

    // Birincil anahtar (UUID, kod tarafında üretilir)
    @Id
    @Column(name = "id")
    private UUID id;

    // Cüzdan sahibi kullanıcı (UNIQUE — kullanıcı başına tek cüzdan)
    @Column(name = "user_id")
    private UUID userId;

    // Güncel bakiye
    @Column(name = "balance")
    private BigDecimal balance;

    // Para birimi (varsayılan TL)
    @Column(name = "currency")
    private String currency;

    // Toplam yüklenen tutar (istatistik)
    @Column(name = "total_topup")
    private BigDecimal totalTopup;

    // Toplam harcanan tutar (istatistik)
    @Column(name = "total_spent")
    private BigDecimal totalSpent;

    // Güncel kredi bakiyesi (FAZ CREDIT)
    @Column(name = "credit_balance")
    private Long creditBalance;

    // Toplam yüklenen kredi (istatistik)
    @Column(name = "total_credit_topup")
    private Long totalCreditTopup;

    // Toplam harcanan kredi (istatistik)
    @Column(name = "total_credit_spent")
    private Long totalCreditSpent;

    // Optimistic lock için sürüm (şimdilik kullanılmıyor)
    @Column(name = "version")
    private Long version;

    // Aktiflik (0/1)
    @Column(name = "active")
    private Integer active;

    // Oluşturulma tarihi
    @Column(name = "created_date")
    private LocalDateTime createdDate;

    // Son güncellenme tarihi
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;
}
