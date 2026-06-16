package com.api.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.repository.UserPaymentLogRepository;
import com.api.dto.repository.UserPaymentRepository;
import com.api.entity.PaymentStatus;
import com.api.entity.TransactionType;
import com.api.entity.UserPayment;
import com.api.entity.UserPaymentLog;

import lombok.RequiredArgsConstructor;

/**
 * Cüzdan/bakiye işlemleri + PayTR ödeme kaydı yönetimi (FAZ PAYMENT).
 *
 * Kurallar (CLAUDE.md): bakiye okuma/güncelleme JdbcTemplate native + '?'; insert JPA saveAndFlush.
 * saveAndFlush ŞART: aynı transaction içinde sonradan gelen native UPDATE satırı görsün
 * (createPending bug'ından öğrenildi). Service interface yok; entity ilişkisi yok.
 */
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
        nw.setVersion(0L);
        nw.setActive(1);
        nw.setCreatedDate(LocalDateTime.now());
        // saveAndFlush: sonraki native UPDATE'ler bu satırı görsün
        return walletRepo.saveAndFlush(nw);
    }

    /** Cüzdanı native sorgu ile getirir (yoksa null). */
    public UserPayment findWallet(UUID userId) {
        String sql = """
                SELECT id, user_id, balance, currency, total_topup, total_spent, version, active
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

    // ---------------------------------------------------------------
    // Bakiye hareketleri (atomik)
    // ---------------------------------------------------------------

    /**
     * Bakiyeden atomik düşüm. Yeterli bakiye yoksa hiç düşmez (0 satır) ve false döner.
     * @return true ise düşüm yapıldı ve DEBIT log'u yazıldı.
     */
    @Transactional
    public boolean tryDebit(UUID userId, BigDecimal amount, UUID reportRequestId) {
        UserPayment w = ensureWallet(userId);
        BigDecimal before = w.getBalance();

        // Atomik koşullu düşüm: yalnızca balance >= amount ise günceller
        String upd = """
                UPDATE user_payment
                SET balance = balance - ?, total_spent = total_spent + ?, version = version + 1, updated_date = ?
                WHERE user_id = ? AND balance >= ?
                """;
        int affected = jdbcTemplate.update(upd, amount, amount, LocalDateTime.now(), userId, amount);
        if (affected == 0) {
            return false; // bakiye yetersiz
        }
        writeMovementLog(userId, w.getId(), reportRequestId, TransactionType.DEBIT, amount,
                before, before.subtract(amount), null);
        return true;
    }

    /** Test/iç kullanım: bakiyeye atomik ekleme + bağımsız TOPUP defter kaydı. */
    @Transactional
    public void topupManual(UUID userId, BigDecimal amount) {
        UserPayment w = ensureWallet(userId);
        BigDecimal before = w.getBalance();
        jdbcTemplate.update("""
                UPDATE user_payment
                SET balance = balance + ?, total_topup = total_topup + ?, version = version + 1, updated_date = ?
                WHERE user_id = ?
                """, amount, amount, LocalDateTime.now(), userId);
        writeMovementLog(userId, w.getId(), null, TransactionType.TOPUP, amount,
                before, before.add(amount), null);
    }

    private void writeMovementLog(UUID userId, UUID walletId, UUID reportRequestId,
            TransactionType type, BigDecimal amount, BigDecimal before, BigDecimal after, String merchantOid) {
        UserPaymentLog log = new UserPaymentLog();
        log.setId(UUID.randomUUID());
        log.setUserId(userId);
        log.setUserPaymentId(walletId);
        log.setReportRequestId(reportRequestId);
        log.setTransactionType(type.name());
        log.setAmount(amount);
        log.setCurrency("TL");
        log.setBalanceBefore(before);
        log.setBalanceAfter(after);
        log.setMerchantOid(merchantOid);
        log.setPaymentProvider("PAYTR");
        log.setCallbackCount(0);
        log.setProcessed(1); // iç hareket doğrudan işlenmiştir
        log.setActive(1);
        log.setCreatedDate(LocalDateTime.now());
        logRepo.saveAndFlush(log);
    }

    // ---------------------------------------------------------------
    // PayTR — STEP 1: ödeme başlatma kaydı (INITIATED) + pending niyet
    // ---------------------------------------------------------------

    /**
     * merchant_oid ile INITIATED log satırı oluşturur (idempotensi anahtarı).
     * amount = deficit (price - balance). pending* = ödeme bitince oluşturulacak rapor isteği niyeti.
     */
    @Transactional
    public UserPaymentLog createInitiatedPayment(UUID userId, BigDecimal amount, String merchantOid,
            LocalDateTime expDate, String pendingReportType, UUID pendingSelectedAccountId) {
        UserPayment w = ensureWallet(userId);

        UserPaymentLog log = new UserPaymentLog();
        log.setId(UUID.randomUUID());
        log.setUserId(userId);
        log.setUserPaymentId(w.getId());
        log.setTransactionType(TransactionType.TOPUP.name());
        log.setAmount(amount);
        log.setCurrency("TL");
        log.setMerchantOid(merchantOid);
        log.setPaymentProvider("PAYTR");
        log.setPaymentStatus(PaymentStatus.INITIATED.name());
        log.setInstallmentCount(0);
        log.setTestMode(0);
        log.setCallbackCount(0);
        log.setProcessed(0); // henüz işlenmedi — callback'te işlenecek
        log.setRequestExpDate(expDate);
        log.setPendingReportType(pendingReportType);
        log.setPendingSelectedAccountId(pendingSelectedAccountId);
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
        public String pendingReportType;
        public UUID pendingSelectedAccountId;
    }

    /**
     * merchant_oid'den INITIATED satırı bulup callback'i işler (idempotent):
     *  - satır yoksa → success=false (bilinmeyen oid; controller "OK" döner)
     *  - processed=1 → alreadyProcessed=true (tekrar bildirim; bakiyeye dokunma)
     *  - success → bakiye yüklenir (bu satır TOPUP defter kaydının kendisidir), processed=1
     *  - failed → FAILED işaretlenir, bakiye yüklenmez, processed=1
     *
     * Hash doğrulaması bu metoda gelmeden ÖNCE controller'da yapılır.
     */
    @Transactional
    public CallbackResult applyCallback(String merchantOid, boolean isSuccess, String totalAmountRaw,
            String paymentType, String failedCode, String failedMsg, String testMode, String rawBody) {

        CallbackResult r = new CallbackResult();
        r.merchantOid = merchantOid;

        UserPaymentLog log = findLogByMerchantOid(merchantOid);
        if (log == null) {
            r.success = false; // bilinmeyen oid
            return r;
        }

        r.userId = log.getUserId();
        r.pendingReportType = log.getPendingReportType();
        r.pendingSelectedAccountId = log.getPendingSelectedAccountId();

        // callback_count++ (her bildirimde) + ham gövdeyi sakla
        jdbcTemplate.update(
                "UPDATE user_payment_log SET callback_count = callback_count + 1, callback_raw = ?, updated_date = ? WHERE id = ?",
                rawBody, LocalDateTime.now(), log.getId());

        // İdempotensi: zaten işlenmişse dokunma
        if (log.getProcessed() != null && log.getProcessed() == 1) {
            r.alreadyProcessed = true;
            r.success = PaymentStatus.SUCCESS.name().equals(log.getPaymentStatus());
            return r;
        }

        BigDecimal paytrTotal = parseKurus(totalAmountRaw);

        if (isSuccess) {
            // Önce SUCCESS + processed=1 (idempotensi kapısı), sonra bakiyeyi yükle
            String upd = """
                    UPDATE user_payment_log
                    SET payment_status = ?, processed = 1, paytr_total_amount = ?, payment_type = ?,
                        test_mode = ?, updated_date = ?
                    WHERE id = ? AND processed = 0
                    """;
            int affected = jdbcTemplate.update(upd, PaymentStatus.SUCCESS.name(), paytrTotal, paymentType,
                    safeInt(testMode), LocalDateTime.now(), log.getId());
            if (affected == 0) {
                // Yarış: başka bir callback bizden önce işledi
                r.alreadyProcessed = true;
                r.success = true;
                return r;
            }
            // Bakiyeyi yükle — bu PayTR satırının KENDİSİ TOPUP defter kaydıdır (ayrı satır yok)
            BigDecimal credit = paytrTotal != null ? paytrTotal : log.getAmount();
            UserPayment w = ensureWallet(log.getUserId());
            BigDecimal before = w.getBalance();
            jdbcTemplate.update("""
                    UPDATE user_payment
                    SET balance = balance + ?, total_topup = total_topup + ?, version = version + 1, updated_date = ?
                    WHERE user_id = ?
                    """, credit, credit, LocalDateTime.now(), log.getUserId());
            jdbcTemplate.update(
                    "UPDATE user_payment_log SET balance_before = ?, balance_after = ?, updated_date = ? WHERE id = ?",
                    before, before.add(credit), LocalDateTime.now(), log.getId());
            r.success = true;
        } else {
            jdbcTemplate.update("""
                    UPDATE user_payment_log
                    SET payment_status = ?, processed = 1, failed_reason_code = ?, failed_reason_msg = ?,
                        test_mode = ?, updated_date = ?
                    WHERE id = ?
                    """,
                    PaymentStatus.FAILED.name(), failedCode, failedMsg, safeInt(testMode),
                    LocalDateTime.now(), log.getId());
            r.success = false;
        }
        return r;
    }

    /** Ödeme sonrası oluşturulan rapor isteğinin id'sini ilgili PayTR log satırına bağlar. */
    @Transactional
    public void linkReportRequest(String merchantOid, UUID reportRequestId) {
        jdbcTemplate.update(
                "UPDATE user_payment_log SET report_request_id = ?, updated_date = ? WHERE merchant_oid = ?",
                reportRequestId, LocalDateTime.now(), merchantOid);
    }

    /** merchant_oid ile log satırını getirir (yoksa null). */
    public UserPaymentLog findLogByMerchantOid(String merchantOid) {
        String sql = """
                SELECT id, user_id, user_payment_id, report_request_id, amount, processed, payment_status,
                       pending_report_type, pending_selected_account_id
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
            l.setPendingReportType(rs.getString("pending_report_type"));
            l.setPendingSelectedAccountId(rs.getObject("pending_selected_account_id", UUID.class));
            return l;
        }, merchantOid);
        return rows.isEmpty() ? null : rows.get(0);
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
