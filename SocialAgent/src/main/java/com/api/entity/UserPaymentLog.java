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
 * Para hareketi + PayTR işlem kaydı (user_payment_log tablosu — FAZ PAYMENT).
 * Her TOPUP/DEBIT/REFUND için bir satır. PayTR akışında idempotensi anahtarı merchant_oid.
 *
 * pending_report_type / pending_selected_account_id: deficit akışında ödeme tamamlanınca
 * oluşturulacak rapor isteğinin niyetini taşır (rapor isteği ödeme bitene kadar oluşturulmaz).
 *
 * İlişkiler yalnızca ID kolonu ile (CLAUDE.md Madde 6). Insert saveAndFlush; güncelleme native.
 */
@Entity
@Table(name = "user_payment_log")
@Getter
@Setter
public class UserPaymentLog {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    // Bağlı cüzdan satırı (user_payment.id)
    @Column(name = "user_payment_id")
    private UUID userPaymentId;

    // Ödeme sonrası oluşturulan rapor isteği (varsa)
    @Column(name = "report_request_id")
    private UUID reportRequestId;

    // Rapor üretilince ScrapePipelineService tarafından doldurulur (audit bütünlüğü)
    @Column(name = "report_id")
    private UUID reportId;

    // TOPUP / DEBIT / REFUND (TransactionType.name())
    @Column(name = "transaction_type")
    private String transactionType;

    // İşlem tutarı (her zaman pozitif)
    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency;

    // Hareket öncesi / sonrası bakiye (audit)
    @Column(name = "balance_before")
    private BigDecimal balanceBefore;

    @Column(name = "balance_after")
    private BigDecimal balanceAfter;

    // Kredi hareketi (FAZ CREDIT) — TOPUP: paket kredisi, DEBIT: harcanan kredi, REFUND: iade edilen kredi
    @Column(name = "credit_amount")
    private Long creditAmount;

    @Column(name = "credit_balance_before")
    private Long creditBalanceBefore;

    @Column(name = "credit_balance_after")
    private Long creditBalanceAfter;

    // DEBIT satırlarında dolu: REPORT | POST | STORY | CAROUSEL
    @Column(name = "product_type")
    private String productType;

    // TOPUP satırlarında dolu: STARTER | STANDARD | PRO | AGENCY
    @Column(name = "package_code")
    private String packageCode;

    // Satın alma anındaki paket adı (snapshot)
    @Column(name = "package_name")
    private String packageName;

    // PayTR sipariş no — idempotensi anahtarı (UNIQUE)
    @Column(name = "merchant_oid")
    private String merchantOid;

    @Column(name = "payment_provider")
    private String paymentProvider;

    // INITIATED / PENDING / SUCCESS / FAILED / EXPIRED (PaymentStatus.name())
    @Column(name = "payment_status")
    private String paymentStatus;

    // Callback total_amount (÷100). Taksit kapalı → amount ile eşit
    @Column(name = "paytr_total_amount")
    private BigDecimal paytrTotalAmount;

    // Callback: card / eft
    @Column(name = "payment_type")
    private String paymentType;

    @Column(name = "installment_count")
    private Integer installmentCount;

    @Column(name = "test_mode")
    private Integer testMode;

    @Column(name = "failed_reason_code")
    private String failedReasonCode;

    @Column(name = "failed_reason_msg")
    private String failedReasonMsg;

    // Kaç kez bildirim geldi (PayTR tekrar gönderebilir)
    @Column(name = "callback_count")
    private Integer callbackCount;

    // Bakiye bu satır için işlendi mi (0/1) — çift işlemeyi önler
    @Column(name = "processed")
    private Integer processed;

    // STEP 1 son geçerlilik
    @Column(name = "request_exp_date")
    private LocalDateTime requestExpDate;

    // Ham callback POST (debug + audit)
    @Column(name = "callback_raw")
    private String callbackRaw;

    // Ödeme tamamlanınca oluşturulacak rapor isteğinin tipi (NONE / OWN_ONLY / COMPETITOR_ONLY)
    @Column(name = "pending_report_type")
    private String pendingReportType;

    // OWN_ONLY niyeti için seçilen kendi hesap id'si
    @Column(name = "pending_selected_account_id")
    private UUID pendingSelectedAccountId;

    @Column(name = "active")
    private Integer active;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "updated_date")
    private LocalDateTime updatedDate;
}
