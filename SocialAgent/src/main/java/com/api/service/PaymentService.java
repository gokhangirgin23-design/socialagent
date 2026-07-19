package com.api.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.config.CreditCatalog;
import com.api.dto.TransactionDto;
import com.api.dto.TransactionsResponse;
import com.api.dto.repository.UserPaymentLogRepository;
import com.api.dto.repository.UserPaymentRepository;
import com.api.entity.PaymentStatus;
import com.api.entity.TransactionType;
import com.api.entity.UserPayment;
import com.api.entity.UserPaymentLog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cüzdan/bakiye işlemleri + PayTR ödeme kaydı yönetimi (FAZ PAYMENT).
 *
 * Kurallar (CLAUDE.md): bakiye okuma/güncelleme JdbcTemplate native + '?'; insert JPA saveAndFlush.
 * saveAndFlush ŞART: aynı transaction içinde sonradan gelen native UPDATE satırı görsün
 * (createPending bug'ından öğrenildi). Service interface yok; entity ilişkisi yok.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final JdbcTemplate jdbcTemplate;
    private final UserPaymentRepository walletRepo;
    private final UserPaymentLogRepository logRepo;

    // ---------------------------------------------------------------
    // Cüzdan
    // ---------------------------------------------------------------

    /** Kullanıcının cüzdanını döndürür; yoksa 0 bakiyeyle oluşturur. */
    @Transactional
    public UserPayment ensureWallet(UUID userId) {
        UserPayment w = findWallet(userId);
        if (w != null) {
            return w;
        }
        UserPayment nw = new UserPayment();
        nw.setId(UUID.randomUUID());
        nw.setUserId(userId);
        nw.setBalance(BigDecimal.ZERO);
        nw.setCurrency("TL");
        nw.setTotalTopup(BigDecimal.ZERO);
        nw.setTotalSpent(BigDecimal.ZERO);
        nw.setCreditBalance(0L);
        nw.setTotalCreditTopup(0L);
        nw.setTotalCreditSpent(0L);
        nw.setVersion(0L);
        nw.setActive(1);
        nw.setCreatedDate(LocalDateTime.now());
        // saveAndFlush: sonraki native UPDATE'ler bu satırı görsün
        return walletRepo.saveAndFlush(nw);
    }

    /** Cüzdanı native sorgu ile getirir (yoksa null). */
    public UserPayment findWallet(UUID userId) {
        String sql = """
                SELECT id, user_id, balance, currency, total_topup, total_spent,
                       credit_balance, total_credit_topup, total_credit_spent, version, active
                FROM user_payment
                WHERE user_id = ?
                """;
        List<UserPayment> rows = jdbcTemplate.query(sql, (rs, i) -> {
            UserPayment w = new UserPayment();
            w.setId(rs.getObject("id", UUID.class));
            w.setUserId(rs.getObject("user_id", UUID.class));
            w.setBalance(rs.getBigDecimal("balance"));
            w.setCurrency(rs.getString("currency"));
            w.setTotalTopup(rs.getBigDecimal("total_topup"));
            w.setTotalSpent(rs.getBigDecimal("total_spent"));
            w.setCreditBalance(rs.getLong("credit_balance"));
            w.setTotalCreditTopup(rs.getLong("total_credit_topup"));
            w.setTotalCreditSpent(rs.getLong("total_credit_spent"));
            w.setVersion(rs.getLong("version"));
            w.setActive(rs.getObject("active", Integer.class));
            return w;
        }, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public BigDecimal getBalance(UUID userId) {
        UserPayment w = findWallet(userId);
        return w == null ? BigDecimal.ZERO : w.getBalance();
    }

    /** Kullanıcının güncel kredi bakiyesi (cüzdan yoksa 0). */
    public long getCreditBalance(UUID userId) {
        UserPayment w = findWallet(userId);
        return w == null || w.getCreditBalance() == null ? 0L : w.getCreditBalance();
    }

    // ---------------------------------------------------------------
    // Kredi hareketleri (atomik) — FAZ CREDIT
    // ---------------------------------------------------------------

    /**
     * Kredi bakiyesinden atomik düşüm. Yeterli kredi yoksa hiç düşmez (0 satır) ve false döner.
     * @return true ise düşüm yapıldı ve DEBIT log'u yazıldı.
     */
    @Transactional
    public boolean tryDebitCredits(UUID userId, int credits, String productType, UUID referenceId) {
        UserPayment w = ensureWallet(userId);
        long before = w.getCreditBalance() == null ? 0L : w.getCreditBalance();

        // Atomik koşullu düşüm: yalnızca credit_balance >= credits ise günceller
        String upd = """
                UPDATE user_payment
                SET credit_balance = credit_balance - ?, total_credit_spent = total_credit_spent + ?,
                    version = version + 1, updated_date = ?
                WHERE user_id = ? AND credit_balance >= ?
                """;
        int affected = jdbcTemplate.update(upd, (long) credits, (long) credits, LocalDateTime.now(), userId, (long) credits);
        if (affected == 0) {
            return false; // kredi yetersiz
        }
        writeCreditMovementLog(userId, w.getId(), referenceId, TransactionType.DEBIT, credits, productType,
                before, before - credits);
        return true;
    }

    /** Başarısız üretimde harcanan krediyi iade eder. */
    @Transactional
    public void refundCredits(UUID userId, int credits, String productType, UUID referenceId) {
        UserPayment w = ensureWallet(userId);
        long before = w.getCreditBalance() == null ? 0L : w.getCreditBalance();
        jdbcTemplate.update("""
                UPDATE user_payment
                SET credit_balance = credit_balance + ?, version = version + 1, updated_date = ?
                WHERE user_id = ?
                """, (long) credits, LocalDateTime.now(), userId);
        writeCreditMovementLog(userId, w.getId(), referenceId, TransactionType.REFUND, credits, productType,
                before, before + credits);
    }

    /** Local/test yardımcısı: krediyi elle yükler (PayTR'a girmeden yeterli-kredi yolunu test etmek için). */
    @Transactional
    public void topupCreditsManual(UUID userId, int credits) {
        UserPayment w = ensureWallet(userId);
        long before = w.getCreditBalance() == null ? 0L : w.getCreditBalance();
        jdbcTemplate.update("""
                UPDATE user_payment
                SET credit_balance = credit_balance + ?, total_credit_topup = total_credit_topup + ?,
                    version = version + 1, updated_date = ?
                WHERE user_id = ?
                """, (long) credits, (long) credits, LocalDateTime.now(), userId);
        writeCreditMovementLog(userId, w.getId(), null, TransactionType.TOPUP, credits, null, before, before + credits);
    }

    private void writeCreditMovementLog(UUID userId, UUID walletId, UUID referenceId,
            TransactionType type, int credits, String productType, long before, long after) {
        UserPaymentLog log = new UserPaymentLog();
        log.setId(UUID.randomUUID());
        log.setUserId(userId);
        log.setUserPaymentId(walletId);
        log.setReportRequestId(referenceId);
        log.setTransactionType(type.name());
        log.setCreditAmount((long) credits);
        log.setCreditBalanceBefore(before);
        log.setCreditBalanceAfter(after);
        log.setProductType(productType);
        log.setPaymentProvider("PAYTR");
        log.setCallbackCount(0);
        log.setProcessed(1); // iç hareket doğrudan işlenmiştir
        log.setActive(1);
        log.setCreatedDate(LocalDateTime.now());
        logRepo.saveAndFlush(log);
    }

    // ---------------------------------------------------------------
    // PayTR — STEP 1: paket satın alma başlatma kaydı (INITIATED)
    // ---------------------------------------------------------------

    /**
     * merchant_oid ile INITIATED log satırı oluşturur (idempotensi anahtarı).
     * amount = paketin TL fiyatı (snapshot); package_code satın alınan paketi taşır.
     * credit_amount/package_name callback'te (SUCCESS) doldurulur.
     */
    @Transactional
    public UserPaymentLog createInitiatedPurchase(UUID userId, CreditCatalog.CreditPackage pkg,
            String merchantOid, LocalDateTime expDate) {
        UserPayment w = ensureWallet(userId);

        UserPaymentLog log = new UserPaymentLog();
        log.setId(UUID.randomUUID());
        log.setUserId(userId);
        log.setUserPaymentId(w.getId());
        log.setTransactionType(TransactionType.TOPUP.name());
        log.setAmount(pkg.priceTl());
        log.setCurrency("TL");
        log.setPackageCode(pkg.code());
        log.setMerchantOid(merchantOid);
        log.setPaymentProvider("PAYTR");
        log.setPaymentStatus(PaymentStatus.INITIATED.name());
        log.setInstallmentCount(0);
        log.setTestMode(0);
        log.setCallbackCount(0);
        log.setProcessed(0); // henüz işlenmedi — callback'te işlenecek
        log.setRequestExpDate(expDate);
        log.setActive(1);
        log.setCreatedDate(LocalDateTime.now());
        return logRepo.saveAndFlush(log);
    }

    // ---------------------------------------------------------------
    // PayTR — STEP 2: callback işleme (idempotent)
    // ---------------------------------------------------------------

    /** Callback işleme sonucu. */
    public static class CallbackResult {
        public boolean success;
        public boolean alreadyProcessed;
        public UUID userId;
        public String merchantOid;
    }

    /**
     * merchant_oid'den INITIATED satırı bulup callback'i işler (idempotent):
     *  - satır yoksa → success=false (bilinmeyen oid; controller "OK" döner)
     *  - processed=1 → alreadyProcessed=true (tekrar bildirim; bakiyeye dokunma)
     *  - success → paketin kredisi yüklenir (bu satır TOPUP defter kaydının kendisidir), processed=1
     *  - failed → FAILED işaretlenir, kredi yüklenmez, processed=1
     *
     * Hash doğrulaması bu metoda gelmeden ÖNCE controller'da yapılır.
     */
    @Transactional
    public CallbackResult applyCallback(String merchantOid, boolean isSuccess, String totalAmountRaw,
            String paymentType, String failedCode, String failedMsg, String testMode, String rawBody) {

        CallbackResult r = new CallbackResult();
        r.merchantOid = merchantOid;

        UserPaymentLog entry = findLogByMerchantOid(merchantOid);
        if (entry == null) {
            r.success = false; // bilinmeyen oid
            return r;
        }

        r.userId = entry.getUserId();

        // callback_count++ (her bildirimde) + ham gövdeyi sakla
        jdbcTemplate.update(
                "UPDATE user_payment_log SET callback_count = callback_count + 1, callback_raw = ?, updated_date = ? WHERE id = ?",
                rawBody, LocalDateTime.now(), entry.getId());

        // İdempotensi: zaten işlenmişse dokunma
        if (entry.getProcessed() != null && entry.getProcessed() == 1) {
            r.alreadyProcessed = true;
            r.success = PaymentStatus.SUCCESS.name().equals(entry.getPaymentStatus());
            return r;
        }

        BigDecimal paytrTotal = parseKurus(totalAmountRaw);

        if (isSuccess) {
            // Önce SUCCESS + processed=1 (idempotensi kapısı), sonra krediyi yükle
            String upd = """
                    UPDATE user_payment_log
                    SET payment_status = ?, processed = 1, paytr_total_amount = ?, payment_type = ?,
                        test_mode = ?, updated_date = ?
                    WHERE id = ? AND processed = 0
                    """;
            int affected = jdbcTemplate.update(upd, PaymentStatus.SUCCESS.name(), paytrTotal, paymentType,
                    safeInt(testMode), LocalDateTime.now(), entry.getId());
            if (affected == 0) {
                // Yarış: başka bir callback bizden önce işledi
                r.alreadyProcessed = true;
                r.success = true;
                return r;
            }
            // Krediyi yükle — bu PayTR satırının KENDİSİ TOPUP defter kaydıdır (ayrı satır yok)
            CreditCatalog.CreditPackage pkg = CreditCatalog.findPackage(entry.getPackageCode());
            if (pkg == null) {
                log.warn("Paket çözülemedi, kredi yüklenemedi: merchantOid={}, packageCode={}",
                        merchantOid, entry.getPackageCode());
            } else {
                if (paytrTotal != null && paytrTotal.compareTo(pkg.priceTl()) != 0) {
                    log.warn("PayTR tutar uyuşmazlığı: merchantOid={}, beklenen={}, gelen={}",
                            merchantOid, pkg.priceTl(), paytrTotal);
                }
                UserPayment w = ensureWallet(entry.getUserId());
                long before = w.getCreditBalance() == null ? 0L : w.getCreditBalance();
                long after = before + pkg.credits();
                jdbcTemplate.update("""
                        UPDATE user_payment
                        SET credit_balance = credit_balance + ?, total_credit_topup = total_credit_topup + ?,
                            version = version + 1, updated_date = ?
                        WHERE user_id = ?
                        """, (long) pkg.credits(), (long) pkg.credits(), LocalDateTime.now(), entry.getUserId());
                jdbcTemplate.update("""
                        UPDATE user_payment_log
                        SET credit_amount = ?, credit_balance_before = ?, credit_balance_after = ?,
                            package_name = ?, updated_date = ?
                        WHERE id = ?
                        """, (long) pkg.credits(), before, after, pkg.name(), LocalDateTime.now(), entry.getId());
            }
            r.success = true;
        } else {
            jdbcTemplate.update("""
                    UPDATE user_payment_log
                    SET payment_status = ?, processed = 1, failed_reason_code = ?, failed_reason_msg = ?,
                        test_mode = ?, updated_date = ?
                    WHERE id = ?
                    """,
                    PaymentStatus.FAILED.name(), failedCode, failedMsg, safeInt(testMode),
                    LocalDateTime.now(), entry.getId());
            r.success = false;
        }
        return r;
    }

    /**
     * DEBIT log'u rapor isteği oluşturulmadan önce yazılır; request oluşturulduktan sonra bu metot
     * en son DEBIT log'unu rapor isteğine bağlar.
     */
    @Transactional
    public void linkLatestDebitToRequest(UUID userId, UUID reportRequestId) {
        // En son report_request_id'si boş DEBIT log'unu bul
        String findSql = """
                SELECT id FROM user_payment_log
                WHERE user_id = ? AND transaction_type = 'DEBIT' AND report_request_id IS NULL
                ORDER BY created_date DESC LIMIT 1
                """;
        List<UUID> ids = jdbcTemplate.query(findSql,
                (rs, i) -> rs.getObject("id", UUID.class), userId);
        if (!ids.isEmpty()) {
            jdbcTemplate.update(
                    "UPDATE user_payment_log SET report_request_id = ?, updated_date = ? WHERE id = ?",
                    reportRequestId, LocalDateTime.now(), ids.get(0));
        }
    }

    /**
     * Rapor üretimi tamamlanınca tüm ilgili ödeme log satırlarına report_id'yi yazar (audit).
     * ScrapePipelineService'ten COMPLETED veya PARTIAL durumunda çağrılır.
     */
    @Transactional
    public void linkReportIdByRequestId(UUID requestId, UUID reportId) {
        jdbcTemplate.update("""
                UPDATE user_payment_log
                SET report_id = ?, updated_date = ?
                WHERE report_request_id = ?
                """, reportId, LocalDateTime.now(), requestId);
    }

    /** merchant_oid ile log satırını getirir (yoksa null). */
    public UserPaymentLog findLogByMerchantOid(String merchantOid) {
        String sql = """
                SELECT id, user_id, user_payment_id, report_request_id, amount, processed, payment_status,
                       package_code
                FROM user_payment_log
                WHERE merchant_oid = ?
                """;
        List<UserPaymentLog> rows = jdbcTemplate.query(sql, (rs, i) -> {
            UserPaymentLog l = new UserPaymentLog();
            l.setId(rs.getObject("id", UUID.class));
            l.setUserId(rs.getObject("user_id", UUID.class));
            l.setUserPaymentId(rs.getObject("user_payment_id", UUID.class));
            l.setReportRequestId(rs.getObject("report_request_id", UUID.class));
            l.setAmount(rs.getBigDecimal("amount"));
            l.setProcessed(rs.getObject("processed", Integer.class));
            l.setPaymentStatus(rs.getString("payment_status"));
            l.setPackageCode(rs.getString("package_code"));
            return l;
        }, merchantOid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Kullanıcının bakiyeye işlenmiş hareketlerini getirir (Hesap Hareketleri — FAZ CREDIT).
     * TOPUP satırları yalnızca payment_status='SUCCESS' AND processed=1 ise döner; INITIATED/FAILED
     * ödeme denemeleri listeye girmez. DEBIT/REFUND tümü döner.
     * Endpoint: POST /payment/transactions
     */
    public TransactionsResponse getTransactionsResponse(UUID userId, int limit, int offset) {
        // limit/offset doğrudan SQL LIMIT/OFFSET'e gidiyor; negatif limit PostgreSQL'de
        // SQLException fırlatıp ham 999 SYSTEM_ERROR'a düşüyordu (H6 bulgusu) — burada
        // anlamlı bir VALIDATION_ERROR'a çevrilir.
        if (limit <= 0) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR, "limit 1 veya daha büyük olmalıdır.");
        }
        if (offset < 0) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR, "offset negatif olamaz.");
        }
        String sql = """
                SELECT transaction_type, credit_amount, amount, package_name, product_type, created_date
                FROM user_payment_log
                WHERE user_id = ?
                  AND (
                        (transaction_type = 'TOPUP' AND payment_status = 'SUCCESS' AND processed = 1)
                     OR transaction_type IN ('DEBIT', 'REFUND')
                  )
                ORDER BY created_date DESC
                LIMIT ? OFFSET ?
                """;
        List<TransactionDto> transactions = jdbcTemplate.query(sql, (rs, i) -> {
            TransactionDto dto = new TransactionDto();
            dto.setTransactionType(rs.getString("transaction_type"));
            dto.setCreditAmount(rs.getObject("credit_amount", Long.class));
            dto.setTlAmount(rs.getBigDecimal("amount"));
            dto.setPackageName(rs.getString("package_name"));
            dto.setProductLabel(productLabel(rs.getString("product_type")));
            java.sql.Timestamp created = rs.getTimestamp("created_date");
            dto.setCreatedDate(created != null ? created.toLocalDateTime() : null);
            return dto;
        }, userId, limit, offset);
        return new TransactionsResponse(transactions, getCreditBalance(userId));
    }

    // DEBIT/REFUND satırlarındaki product_type -> Türkçe kullanıcı etiketi
    private String productLabel(String productType) {
        if (productType == null) {
            return null;
        }
        return switch (productType) {
            case "REPORT" -> "Rapor Oluşturma";
            case "POST" -> "Post Üretimi";
            case "STORY" -> "Story Üretimi";
            case "CAROUSEL" -> "Carousel Üretimi";
            default -> productType;
        };
    }

    // total_amount ×100 (kuruş) → TL BigDecimal
    private BigDecimal parseKurus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).movePointLeft(2);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer safeInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
