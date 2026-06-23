package com.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.api.entity.UserPayment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.config.AppProperties;
import com.api.dto.AvailableTypesResponseDto;
import com.api.dto.TopupResponse;
import com.api.dto.WalletDto;
import com.api.dto.BalanceCheckResponse;
import com.api.dto.CreateReportRequestDto;
import com.api.dto.PaytrFormPayload;
import com.api.dto.ReportRequestDto;
import com.api.dto.repository.ReportRequestRepository;
import com.api.entity.AnalysisMode;
import com.api.entity.ReportRequest;
import com.api.mapper.ReportRequestMapper;
import com.api.messaging.JobQueueProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rapor isteği oluşturma, listeleme ve seçilebilirlik iş mantığı.
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Temel akış:
 *   1) reportType kullanıcı tarafından açıkça seçilir (otomatik belirlenmez).
 *   2) İstek tabloya eklenir, ardından direkt kuyruğa basılır (scheduler yok).
 *   3) Kuyruk FIFO mantığıyla çalışır; worker pipeline'ı tetikler.
 *
 * Doğrulama:
 *   - OWN_ONLY / BOTH seçilmişse kullanıcının aktif kendi hesabı olmalı.
 *   - COMPETITOR_ONLY / BOTH seçilmişse en az bir izlenen hesap olmalı.
 *   - NONE / OWN_ONLY seçilmişse sektör seçili olmalı (hashtag araştırması için).
 *
 * Lookup'lar JdbcTemplate native + text-block SQL + "?" (CLAUDE.md Madde 6).
 * Insert JPA save, güncelleme (queue_pushed flag) native UPDATE.
 * userId daima JWT'den (CLAUDE.md Madde 4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportRequestService {

    // Native sorgular için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // report_request insert için JPA repository
    private final ReportRequestRepository reportRequestRepository;

    // ReportRequest entity -> DTO dönüştürücü
    private final ReportRequestMapper reportRequestMapper;

    // Kuyruğa basan producer
    private final JobQueueProducer jobQueueProducer;

    // ===== FAZ PAYMENT: bakiye kapısı bağımlılıkları =====
    // Cüzdan/bakiye işlemleri + PayTR ödeme kaydı
    private final PaymentService paymentService;
    // PayTR form üretimi (local'de LocalPaytrGateway enjekte edilir)
    private final PaytrGateway paytrGateway;
    // report_type → fiyat çözümleyici
    private final ReportPriceResolver reportPriceResolver;
    // Ödeme kapısı bayrak (app.payment.enabled = false → bakiye/PayTR atlanır)
    private final AppProperties appProperties;

    /**
     * Yeni rapor isteği oluşturur (FAZ PAYMENT — bakiye kapısı).
     * reportType istekten gelir; hesap doluluk durumuna göre OTOMATİK BELİRLENMEZ.
     *
     * Akış:
     *   1) Validasyon (mod, hesap, sektör) — eskisiyle aynı.
     *   2) Fiyat belirle. Bakiye YETERLİ ise: atomik DEBIT → rapor isteği oluştur + kuyruğa bas (COMPLETED).
     *   3) Bakiye YETERSİZ ise: rapor isteği OLUŞTURULMAZ; niyet PayTR ödeme kaydına yazılır,
     *      eksik tutar (deficit = price - balance) için PayTR form payload'ı döner (PAYMENT_REQUIRED).
     *      Ödeme başarılı callback'inde rapor isteği oluşturulup kuyruğa basılır (deficit modeli).
     *
     * Endpoint: POST /report-request/create
     *
     * @param clientIp PayTR STEP 1 token'ı user_ip ister (controller'dan gelir; local'de kullanılmaz)
     */
    @Transactional
    public ReportRequestDto createRequest(UUID userId, CreateReportRequestDto req, String clientIp) {

        // 1) Validasyon (mod, hesap, sektör)
        AnalysisMode mode = parseAnalysisMode(req.getReportType());
        if (mode == AnalysisMode.BOTH) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "BOTH modu desteklenmemektedir. Geçerli tipler: NONE, OWN_ONLY, COMPETITOR_ONLY");
        }
        UUID ownAccountId = null;
        if (mode == AnalysisMode.OWN_ONLY) {
            ownAccountId = findOwnAccountId(userId);
            if (ownAccountId == null) {
                throw new ApiException(ResponseCode.VALIDATION_ERROR,
                        "OWN_ONLY modu için aktif kendi hesabınız bulunmamaktadır. Önce hesap ekleyin.");
            }
        }
        if (mode == AnalysisMode.COMPETITOR_ONLY && !hasMonitoredAccounts(userId)) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "COMPETITOR_ONLY modu için izlenen rakip hesap bulunmamaktadır. Önce rakip hesap ekleyin.");
        }
        if ((mode == AnalysisMode.NONE || mode == AnalysisMode.OWN_ONLY) && !hasSectorSelected(userId)) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "Sektör araştırması için önce sektör seçilmelidir.");
        }

        // 2) Ödeme kapısı kapalıysa bakiye/PayTR atlanır; doğrudan istek oluşturulur (PAYMENT_ENABLED=false)
        if (!appProperties.getPayment().isEnabled()) {
            log.info("Ödeme kapısı kapalı; rapor isteği ücretsiz oluşturuldu: userId={}, tip={}", userId, mode);
            ReportRequestDto freeDto = persistAndQueue(userId, mode, ownAccountId);
            freeDto.setPaymentRequired(false);
            return freeDto;
        }

        // 3) Fiyat + bakiye kapısı
        BigDecimal price = reportPriceResolver.priceFor(mode);
        BigDecimal balance = paymentService.getBalance(userId);

        if (balance.compareTo(price) >= 0 && paymentService.tryDebit(userId, price, null)) {
            // Bakiye yeterli → ücret düşüldü → rapor isteğini oluştur ve kuyruğa bas
            ReportRequestDto dto = persistAndQueue(userId, mode, ownAccountId);
            // Doğrudan ödeme: DEBIT log request oluşturulmadan yazıldı; geriye dönük bağla
            paymentService.linkLatestDebitToRequest(userId, dto.getRequestId());
            dto.setPaymentRequired(false);
            return dto;
        }

        // 4) Bakiye yetersiz → PayTR ödemesi başlat (rapor isteği henüz OLUŞTURULMAZ)
        BigDecimal deficit = price.subtract(balance);
        if (deficit.compareTo(BigDecimal.ZERO) <= 0) {
            deficit = price; // güvenlik
        }
        // Kullanıcı daha fazla yüklemek isteyebilir (topupAmount > deficit); fazlası bakiyede kalır
        BigDecimal payAmount = (req.getTopupAmount() != null
                && req.getTopupAmount().compareTo(deficit) > 0)
                        ? req.getTopupAmount()
                        : deficit;
        String merchantOid = generateMerchantOid();
        LocalDateTime exp = LocalDateTime.now().plusMinutes(30); // PayTR varsayılanı 30 dk
        // Niyet (reportType + seçilen hesap) ödeme kaydına yazılır; callback'te rapor isteği oluşturulur
        paymentService.createInitiatedPayment(userId, payAmount, merchantOid, exp, mode.name(), ownAccountId);

        String email = lookupEmail(userId);
        PaytrFormPayload payload = paytrGateway.buildPaymentForm(merchantOid, clientIp, email, payAmount);

        // PAYMENT_REQUIRED yanıtı (rapor isteği henüz yok → requestId null)
        ReportRequestDto dto = new ReportRequestDto();
        dto.setUserId(userId);
        dto.setReportType(mode.name());
        dto.setQueuePushed(0);
        dto.setPaymentRequired(true);
        dto.setAmountToPay(payAmount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        dto.setPaytr(payload);
        return dto;
    }

    /**
     * PayTR success callback'inden sonra çağrılır (PaymentCallbackController).
     * Bakiye zaten yüklendi; burada DEBIT(price) + rapor isteği oluşturma + kuyruğa basma yapılır.
     * İdempotensi callback tarafında (processed) sağlanır; burada bakiye yetersizse sessizce çıkılır.
     */
    @Transactional
    public void fulfillPaidRequest(UUID userId, String reportType, UUID selectedAccountId, String merchantOid) {
        AnalysisMode mode = parseAnalysisMode(reportType);
        BigDecimal price = reportPriceResolver.priceFor(mode);

        // Bakiyeden rapor ücretini düş (topup sonrası yeterli olmalı)
        if (!paymentService.tryDebit(userId, price, null)) {
            log.warn("Ödeme sonrası bakiye beklenenden az; rapor isteği oluşturulmadı: userId={}, merchant_oid={}",
                    userId, merchantOid);
            return;
        }
        // Rapor isteğini oluştur + kuyruğa bas, ardından ödeme kayıtlarına bağla
        ReportRequestDto dto = persistAndQueue(userId, mode, selectedAccountId);
        paymentService.linkReportRequest(merchantOid, dto.getRequestId()); // TOPUP log bağlantısı
        paymentService.linkLatestDebitToRequest(userId, dto.getRequestId()); // DEBIT log bağlantısı
        log.info("Ödeme tamamlandı, rapor isteği oluşturuldu: requestId={}, merchant_oid={}",
                dto.getRequestId(), merchantOid);
    }

    /**
     * report_request kaydını oluşturur ve kuyruğa basar (eski adım 6-7; bakiye düşümünden SONRA çağrılır).
     */
    private ReportRequestDto persistAndQueue(UUID userId, AnalysisMode mode, UUID ownAccountId) {
        LocalDateTime now = LocalDateTime.now();

        ReportRequest request = new ReportRequest();
        request.setRequestId(UUID.randomUUID());
        request.setUserId(userId);
        request.setReportType(mode.name());
        request.setSelectedUserSocialAccountId(ownAccountId);
        request.setQueuePushed(0);
        request.setStatus("PENDING");    // V2: NOT NULL, DEFAULT 'PENDING'
        request.setAttemptCount(0);      // V2: NOT NULL, DEFAULT 0
        request.setActive(1);
        request.setCreatedDate(now);
        request.setUpdatedDate(now);

        // saveAndFlush: TX commit öncesi INSERT'i veritabanına gönderir (henüz commit değil)
        ReportRequest saved = reportRequestRepository.saveAndFlush(request);
        UUID finalRequestId = saved.getRequestId();

        // Race condition önlemi: RabbitMQ mesajını TX commit'ten SONRA gönder.
        // Aksi hâlde worker DB'de kaydı henüz göremeden işlemeye çalışır.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    jobQueueProducer.publishRequest(finalRequestId);
                    jdbcTemplate.update(
                            "UPDATE report_request SET queue_pushed = 1, queue_push_date = ?, updated_date = ? WHERE request_id = ?",
                            Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()), finalRequestId);
                    log.info("Rapor isteği TX commit sonrası kuyruğa basıldı: requestId={}, userId={}, tip={}",
                            finalRequestId, userId, mode);
                } catch (Exception ex) {
                    String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    jdbcTemplate.update(
                            "UPDATE report_request SET queue_error = ?, updated_date = ? WHERE request_id = ?",
                            errorMsg, Timestamp.valueOf(LocalDateTime.now()), finalRequestId);
                    log.warn("TX commit sonrası kuyruğa basılamadı: requestId={}, hata={}", finalRequestId, errorMsg);
                }
            }
        });

        log.info("Rapor isteği oluşturuldu (TX commit bekleniyor): requestId={}, userId={}, tip={}",
                finalRequestId, userId, mode);
        return reportRequestMapper.toDto(saved);
    }

    /** Kullanıcının e-postası (PayTR formu için). */
    private String lookupEmail(UUID userId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT email FROM user_info WHERE user_id = ? AND active = 1",
                (rs, i) -> rs.getString("email"), userId);
        if (!rows.isEmpty() && rows.get(0) != null && !rows.get(0).isBlank()) {
            return rows.get(0);
        }
        return "noreply@spectiqs.com";
    }

    /** Tekil, alfanümerik, ≤64 karakter merchant_oid (PayTR kuralı). */
    private String generateMerchantOid() {
        String rnd = UUID.randomUUID().toString().replace("-", "");
        return "TR" + System.currentTimeMillis() + rnd.substring(0, 8);
    }

    /**
     * Seçilen rapor tipine göre bakiye yeterlilik kontrolü döner.
     * Frontend bu bilgiyle "bakiyeniz yetersiz" uyarısını ve minimum yükleme tutarını gösterir.
     * Endpoint: POST /payment/balance-check
     */
    @Transactional(readOnly = true)
    public BalanceCheckResponse checkBalance(UUID userId, String reportType) {
        AnalysisMode mode = parseAnalysisMode(reportType);
        BigDecimal price = reportPriceResolver.priceFor(mode);
        BigDecimal balance = paymentService.getBalance(userId);
        boolean sufficient = balance.compareTo(price) >= 0;
        BigDecimal deficit = sufficient
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : price.subtract(balance).setScale(2, RoundingMode.HALF_UP);
        BalanceCheckResponse resp = new BalanceCheckResponse();
        resp.setBalance(balance.setScale(2, RoundingMode.HALF_UP));
        resp.setPrice(price.setScale(2, RoundingMode.HALF_UP));
        resp.setSufficient(sufficient);
        resp.setDeficit(deficit);
        resp.setCurrency("TL");
        return resp;
    }

    /**
     * Kullanıcının rapor isteklerini sayfalı listeler (en yeni önce).
     * Endpoint: POST /report-request/list
     */
    @Transactional(readOnly = true)
    public List<ReportRequestDto> listRequests(UUID userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = (size > 0) ? size : 10;
        int offset = safePage * safeSize;

        String sql = """
                SELECT request_id, user_id, report_type, selected_user_social_account_id,
                       queue_pushed, queue_push_date, queue_error,
                       status, process_error, process_started_date, process_finished_date,
                       active, created_date, updated_date
                FROM report_request
                WHERE user_id = ? AND active = 1
                ORDER BY created_date DESC
                LIMIT ? OFFSET ?
                """;
        List<ReportRequest> requests = jdbcTemplate.query(sql, REQUEST_ROW_MAPPER, userId, safeSize, offset);
        return reportRequestMapper.toDtoList(requests);
    }

    /**
     * Kullanıcının seçebileceği analiz türlerini fiyat + cüzdan bakiyesiyle döndürür.
     * NONE daima listelenir; OWN_ONLY kendi hesabı varsa, COMPETITOR_ONLY rakip hesabı varsa eklenir.
     * Endpoint: POST /report-request/available-types
     */
    @Transactional(readOnly = true)
    public AvailableTypesResponseDto getAnalysisSelectability(UUID userId) {
        boolean hasOwn = findOwnAccountId(userId) != null;
        boolean hasMonitored = hasMonitoredAccounts(userId);
        BigDecimal balance = paymentService.getBalance(userId);

        List<AvailableTypesResponseDto.ReportTypeOption> types = new ArrayList<>();
        // NONE: sektör analizi — her zaman seçilebilir
        types.add(new AvailableTypesResponseDto.ReportTypeOption(
                "NONE", "Sektör analizi", reportPriceResolver.priceFor(AnalysisMode.NONE)));
        // OWN_ONLY: kendi hesabı varsa seçilebilir
        if (hasOwn) {
            types.add(new AvailableTypesResponseDto.ReportTypeOption(
                    "OWN_ONLY", "Kendi hesabım analizi", reportPriceResolver.priceFor(AnalysisMode.OWN_ONLY)));
        }
        // COMPETITOR_ONLY: en az 1 rakip hesabı varsa seçilebilir
        if (hasMonitored) {
            types.add(new AvailableTypesResponseDto.ReportTypeOption(
                    "COMPETITOR_ONLY", "Rakip hesap analizi",
                    reportPriceResolver.priceFor(AnalysisMode.COMPETITOR_ONLY)));
        }

        return AvailableTypesResponseDto.builder()
                .currency("TL")
                .balance(balance.setScale(2, RoundingMode.HALF_UP))
                .types(types)
                .build();
    }

    /**
     * Rapordan bağımsız bakiye yükleme (standalone topup).
     * Mevcut deficit akışının PayTR init mantığını paylaşır; rapor niyeti null'dır.
     * Endpoint: POST /payment/topup
     */
    @Transactional
    public TopupResponse initiateTopup(UUID userId, BigDecimal amount, String clientIp) {
        String merchantOid = generateMerchantOid();
        LocalDateTime exp = LocalDateTime.now().plusMinutes(30);
        // Standalone topup: pendingReportType + pendingSelectedAccountId null (rapor niyeti yok)
        paymentService.createInitiatedPayment(userId, amount, merchantOid, exp, null, null);
        String email = lookupEmail(userId);
        PaytrFormPayload payload = paytrGateway.buildPaymentForm(merchantOid, clientIp, email, amount);
        return new TopupResponse(merchantOid, payload);
    }

    /**
     * Kullanıcının güncel cüzdan bilgisini döndürür.
     * Endpoint: POST /payment/wallet
     */
    @Transactional(readOnly = true)
    public WalletDto getWalletDto(UUID userId) {
        UserPayment w = paymentService.findWallet(userId);
        if (w == null) {
            // Cüzdan henüz oluşturulmamış; sıfır bakiye döner
            return new WalletDto(
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), "TL",
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        return new WalletDto(
                w.getBalance().setScale(2, RoundingMode.HALF_UP),
                w.getCurrency() != null ? w.getCurrency() : "TL",
                w.getTotalTopup().setScale(2, RoundingMode.HALF_UP),
                w.getTotalSpent().setScale(2, RoundingMode.HALF_UP));
    }

    // ============================================================
    // Yardımcı metodlar
    // ============================================================

    /**
     * Kullanıcının aktif kendi sosyal hesabının id'sini döndürür; yoksa null.
     */
    private UUID findOwnAccountId(UUID userId) {
        String sql = """
                SELECT user_social_account_id
                FROM user_social_account
                WHERE user_id = ? AND active = 1
                LIMIT 1
                """;
        List<UUID> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getObject("user_social_account_id", UUID.class),
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Kullanıcının en az bir aktif izlenen rakip hesabı var mı?
     */
    private boolean hasMonitoredAccounts(UUID userId) {
        String sql = """
                SELECT user_monitored_account_id
                FROM user_monitored_account
                WHERE user_id = ? AND active = 1
                LIMIT 1
                """;
        List<UUID> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getObject("user_monitored_account_id", UUID.class),
                userId);
        return !rows.isEmpty();
    }

    /**
     * Kullanıcı sektör seçmiş mi? (NONE / OWN_ONLY için ön koşul).
     */
    private boolean hasSectorSelected(UUID userId) {
        String sql = """
                SELECT sector_id
                FROM user_info
                WHERE user_id = ? AND active = 1
                """;
        List<UUID> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getObject("sector_id", UUID.class),
                userId);
        return !rows.isEmpty() && rows.get(0) != null;
    }

    /**
     * String değeri AnalysisMode enum'una çevirir; geçersiz değerde VALIDATION_ERROR fırlatır.
     */
    private AnalysisMode parseAnalysisMode(String value) {
        try {
            return AnalysisMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "Geçersiz reportType değeri: " + value + ". Geçerli değerler: NONE, OWN_ONLY, COMPETITOR_ONLY");
        }
    }

    // Requeue poison guard: aynı istek en fazla 3 kez yeniden kuyruğa basılabilir
    private static final int MAX_ATTEMPTS = 3;
    // Bu süreden uzun PROCESSING/PENDING kalan istek "takılı" sayılır
    private static final int STUCK_MINUTES = 30;

    /**
     * V2: Status-bazlı sweep — FAILED/PARTIAL veya takılı (eski PROCESSING/PENDING) istekleri
     * attempt_count < MAX_ATTEMPTS koşuluyla yeniden kuyruğa alır.
     * Admin tarafından POST /admin/requeue-stuck ile elle tetiklenir; otomatik scheduler yok.
     *
     * Seçim: FAILED | PARTIAL; ya da PROCESSING/PENDING olup STUCK_MINUTES'tan uzun bekleyen.
     * Güncelleme: status='PENDING', attempt_count++, queue_error=NULL.
     *
     * @return yeniden kuyruğa basılan kayıt sayısı
     */
    @Transactional
    public int requeueStuck() {
        // Eşik zaman damgası Java'da hesaplanır (H2/PostgreSQL uyumu; interval SQL'i kullanılmaz)
        Timestamp stuckThreshold = Timestamp.valueOf(LocalDateTime.now().minusMinutes(STUCK_MINUTES));

        // V2 status-bazlı seçim + attempt_count poison guard
        String findSql = """
                SELECT request_id
                FROM report_request
                WHERE active = 1
                  AND attempt_count < ?
                  AND (
                        status IN ('FAILED', 'PARTIAL')
                     OR (status = 'PROCESSING' AND process_started_date < ?)
                     OR (status = 'PENDING'    AND queue_push_date    < ?)
                  )
                """;
        List<UUID> stuckIds = jdbcTemplate.query(findSql,
                (rs, rowNum) -> rs.getObject("request_id", UUID.class),
                MAX_ATTEMPTS, stuckThreshold, stuckThreshold);

        if (stuckIds.isEmpty()) {
            log.info("Tekrar kuyruğa basılacak takılı rapor isteği bulunamadı.");
            return 0;
        }

        int success = 0;
        LocalDateTime now = LocalDateTime.now();
        List<UUID> failed = new ArrayList<>();

        for (UUID requestId : stuckIds) {
            try {
                jobQueueProducer.publishRequest(requestId);
                // V2: status PENDING'e çek, attempt_count artır, queue_error temizle
                jdbcTemplate.update("""
                        UPDATE report_request
                        SET queue_pushed = 1, queue_push_date = ?, queue_error = NULL,
                            status = 'PENDING', attempt_count = attempt_count + 1,
                            updated_date = ?
                        WHERE request_id = ?
                        """, Timestamp.valueOf(now), Timestamp.valueOf(now), requestId);
                log.info("Takılı istek yeniden kuyruğa basıldı: requestId={}", requestId);
                success++;
            } catch (Exception ex) {
                String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                jdbcTemplate.update(
                        "UPDATE report_request SET queue_error = ?, updated_date = ? WHERE request_id = ?",
                        "requeue-failed: " + errMsg, Timestamp.valueOf(now), requestId);
                log.warn("Takılı istek yeniden kuyruğa basılamadı: requestId={}, hata={}", requestId, errMsg);
                failed.add(requestId);
            }
        }
        log.info("Requeue tamamlandı: toplam={}, basılan={}, başarısız={}", stuckIds.size(), success, failed.size());
        return success;
    }

    // report_request satırını entity'ye çeviren RowMapper (liste sorguları için)
    private static final RowMapper<ReportRequest> REQUEST_ROW_MAPPER = (rs, rowNum) -> {
        ReportRequest r = new ReportRequest();
        r.setRequestId(rs.getObject("request_id", UUID.class));
        r.setUserId(rs.getObject("user_id", UUID.class));
        r.setReportType(rs.getString("report_type"));
        r.setSelectedUserSocialAccountId(rs.getObject("selected_user_social_account_id", UUID.class));
        r.setQueuePushed(rs.getObject("queue_pushed", Integer.class));
        if (rs.getTimestamp("queue_push_date") != null) {
            r.setQueuePushDate(rs.getTimestamp("queue_push_date").toLocalDateTime());
        }
        r.setQueueError(rs.getString("queue_error"));
        // V2: işleme durumu ve hata bilgisi
        r.setStatus(rs.getString("status"));
        r.setProcessError(rs.getString("process_error"));
        if (rs.getTimestamp("process_started_date") != null) {
            r.setProcessStartedDate(rs.getTimestamp("process_started_date").toLocalDateTime());
        }
        if (rs.getTimestamp("process_finished_date") != null) {
            r.setProcessFinishedDate(rs.getTimestamp("process_finished_date").toLocalDateTime());
        }
        r.setActive(rs.getObject("active", Integer.class));
        if (rs.getTimestamp("created_date") != null) {
            r.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
        }
        if (rs.getTimestamp("updated_date") != null) {
            r.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
        }
        return r;
    };
}
