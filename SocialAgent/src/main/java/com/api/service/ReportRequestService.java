package com.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.api.entity.UserPayment;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.config.AppProperties;
import com.api.config.CreditCatalog;
import com.api.dto.AvailableTypesResponseDto;
import com.api.dto.PackageDto;
import com.api.dto.PackagesResponse;
import com.api.dto.PurchaseResponse;
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
 * Temel akış (Geliştirme 2 — tek tip rapor + otomatik mod seçimi):
 *   1) reportType artık kullanıcıdan ALINMAZ; kendi/rakip hesap durumuna göre backend otomatik belirler.
 *   2) İstek tabloya eklenir, ardından direkt kuyruğa basılır (scheduler yok).
 *   3) Kuyruk FIFO mantığıyla çalışır; worker pipeline'ı tetikler.
 *
 * Mod otomatik belirleme kuralları:
 *   - Kullanıcının aktif kendi hesabı YOKSA → engellenir, hiçbir kayıt oluşturulmaz.
 *   - Kendi hesabı VAR + en az 1 izlenen rakip hesap VARSA → mode = BOTH.
 *   - Kendi hesabı VAR + rakip hesap YOKSA → mode = OWN_ONLY; bu durumda sektör seçili olmalı.
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

    // ===== FAZ CREDIT: kredi kapısı bağımlılıkları =====
    // Cüzdan/kredi işlemleri + PayTR ödeme kaydı
    private final PaymentService paymentService;
    // PayTR form üretimi (local'de LocalPaytrGateway enjekte edilir)
    private final PaytrGateway paytrGateway;
    // report_type → kredi maliyeti çözümleyici
    private final ReportPriceResolver reportPriceResolver;
    // Ödeme kapısı bayrak (app.payment.enabled = false → kredi kontrolü atlanır)
    private final AppProperties appProperties;
    // Ücretsiz ilk kullanım hakkı (V11) — kredi sistemine dokunmadan ayrı tablodan kontrol/kayıt
    private final FreeUsageService freeUsageService;

    /**
     * Yeni rapor isteği oluşturur (FAZ CREDIT — kredi kapısı).
     * Geliştirme 2: reportType istekten OKUNMAZ; mod otomatik OWN_ONLY'dir (rakip hesap özelliği
     * kaldırıldığından BOTH artık hiç üretilmez).
     *
     * Akış:
     *   1) Validasyon (kendi hesap zorunlu; sektör zorunlu).
     *   2) Kredi YETERLİ ise: rapor isteği oluştur + kuyruğa bas (kredi düşümü COMPLETED'de yapılır, #40).
     *   3) Kredi YETERSİZ ise: rapor isteği OLUŞTURULMAZ; insufficientCredits=true + requiredCredits
     *      + creditBalance döner (kullanıcı /payment/packages üzerinden paket satın almalıdır).
     *
     * Endpoint: POST /report-request/create
     */
    @Transactional
    public ReportRequestDto createRequest(UUID userId, CreateReportRequestDto req) {

        // 1) Validasyon — kendi hesap yoksa hiçbir kayıt oluşturulmadan/kredi kontrolüne
        // girilmeden anında engellenir; sektör yoksa da aynı şekilde.
        UUID ownAccountId = findOwnAccountId(userId);
        if (ownAccountId == null) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "Rapor oluşturmak için önce kendi hesabınızı eklemelisiniz.");
        }
        if (!hasSectorSelected(userId)) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "Sektör analizi için önce sektör seçmelisiniz.");
        }
        AnalysisMode mode = AnalysisMode.OWN_ONLY;

        // 2) Ödeme kapısı kapalıysa kredi kontrolü atlanır; doğrudan istek oluşturulur (PAYMENT_ENABLED=false)
        if (!appProperties.getPayment().isEnabled()) {
            log.info("Ödeme kapısı kapalı; rapor isteği ücretsiz oluşturuldu: userId={}, tip={}", userId, mode);
            ReportRequestDto freeDto = persistAndQueue(userId, mode, ownAccountId, false);
            freeDto.setInsufficientCredits(false);
            return freeDto;
        }

        // 2.5) Ücretsiz ilk kullanım hakkı (V11) — kredi kontrolünden ÖNCE kontrol edilir; varsa
        // kredi hiç düşülmeden istek oluşturulur. active_lock_key (E7) kullanıcı başına eşzamanlı
        // tek istek garanti ettiğinden burada ek bir yarış koruması gerekmez.
        if (freeUsageService.isFreeReportAvailable(userId)) {
            log.info("Ücretsiz ilk rapor hakkı kullanılıyor: userId={}, tip={}", userId, mode);
            ReportRequestDto freeDto = persistAndQueue(userId, mode, ownAccountId, true);
            freeDto.setInsufficientCredits(false);
            return freeDto;
        }

        // 3) Kredi kapısı
        int requiredCredits = reportPriceResolver.creditCostFor(mode);
        long creditBalance = paymentService.getCreditBalance(userId);

        if (creditBalance < requiredCredits) {
            // Kredi yetersiz → rapor isteği OLUŞTURULMAZ; kullanıcı paket satın almaya yönlendirilir
            ReportRequestDto dto = new ReportRequestDto();
            dto.setUserId(userId);
            dto.setReportType(mode.name());
            dto.setQueuePushed(0);
            dto.setInsufficientCredits(true);
            dto.setRequiredCredits(requiredCredits);
            dto.setCreditBalance(creditBalance);
            return dto;
        }

        // Kredi yeterli → rapor isteğini oluştur; kredi düşümü pipeline COMPLETED olduğunda yapılır (#40)
        ReportRequestDto dto = persistAndQueue(userId, mode, ownAccountId, false);
        dto.setInsufficientCredits(false);
        return dto;
    }

    /**
     * report_request kaydını oluşturur ve kuyruğa basar (eski adım 6-7; bakiye düşümünden SONRA çağrılır).
     *
     * E7 fix — çift-tık/duplicate koruması: önce dostane bir ön kontrol (SELECT) yapılır; ancak bu
     * tek başına eşzamanlı iki isteği (~ms arayla, hatta 2 farklı app instance'ında) engelleyemez.
     * Gerçek atomiklik active_lock_key üzerindeki DB UNIQUE constraint'i ile sağlanır (bkz.
     * ReportRequest.activeLockKey javadoc'u) — saveAndFlush çakışırsa DataIntegrityViolationException
     * yakalanıp kullanıcıya "zaten aktif bir isteğiniz var" hatası olarak döndürülür.
     */
    private ReportRequestDto persistAndQueue(UUID userId, AnalysisMode mode, UUID ownAccountId, boolean isFreeUsage) {
        LocalDateTime now = LocalDateTime.now();

        // Ön kontrol: kullanıcının zaten PENDING/PROCESSING bir isteği var mı? (dostane, hızlı yol)
        String activeSql = """
                SELECT request_id FROM report_request
                WHERE user_id = ? AND active = 1 AND status IN ('PENDING', 'PROCESSING')
                LIMIT 1
                """;
        List<UUID> activeRows = jdbcTemplate.query(activeSql,
                (rs, rowNum) -> rs.getObject("request_id", UUID.class), userId);
        if (!activeRows.isEmpty()) {
            throw new ApiException(ResponseCode.DUPLICATE,
                    "Zaten işlenmekte olan bir rapor isteğiniz var. Lütfen tamamlanmasını bekleyin.");
        }

        // V10: rapor üretim ANINDAKİ sektör/alt sektörü dondur (canlı user_info'ya join değil —
        // kullanıcı sonradan sektör değiştirirse eski raporlar yanlış görünmesin)
        UUID[] sectorSnapshot = lookupUserSectorSnapshot(userId);
        // V12: rapor üretim ANINDAKİ kendi hesap adını dondur (AccountService hesabı yerinde
        // yeniden adlandırabildiğinden — canlı join eski raporların adını değiştirebilirdi)
        String ownAccountNameSnapshot = ownAccountId != null ? lookupAccountName(ownAccountId) : null;

        ReportRequest request = new ReportRequest();
        request.setRequestId(UUID.randomUUID());
        request.setUserId(userId);
        request.setReportType(mode.name());
        request.setSelectedUserSocialAccountId(ownAccountId);
        request.setSectorId(sectorSnapshot[0]);
        request.setSubsectorId(sectorSnapshot[1]);
        request.setOwnAccountName(ownAccountNameSnapshot);
        request.setIsFreeUsage(isFreeUsage ? 1 : 0);
        request.setQueuePushed(0);
        request.setStatus("PENDING");    // V2: NOT NULL, DEFAULT 'PENDING'
        request.setAttemptCount(0);      // V2: NOT NULL, DEFAULT 0
        request.setActive(1);
        request.setCreatedDate(now);
        request.setUpdatedDate(now);
        request.setActiveLockKey(userId); // V7: terminal duruma geçince NULL'a döner (markFinished)

        // saveAndFlush: TX commit öncesi INSERT'i veritabanına gönderir (henüz commit değil).
        // Eşzamanlı iki istek buraya aynı anda ulaşırsa (ön kontrolü ikisi de geçmiş olabilir),
        // active_lock_key UNIQUE constraint'i yalnızca birinin başarılı olmasını garanti eder.
        ReportRequest saved;
        try {
            saved = reportRequestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException ex) {
            // YALNIZCA active_lock_key çakışmasıysa DUPLICATE'e çevrilir. Aksi halde (ör. başka bir
            // NOT NULL/constraint ihlali) gerçek hata MASKELENMEDEN fırlatılır — aksi halde tamamen
            // farklı bir bug (ör. eksik Java-side default) yanlışlıkla "duplicate rapor" gibi
            // görünüp teşhisi çok zorlaştırır (bu tam olarak canlıda yaşandı, bkz. credit_debited
            // NOT NULL ihlali insidenti).
            String rootMsg = rootCauseMessage(ex);
            if (rootMsg != null && rootMsg.contains("uq_report_request_active_lock")) {
                log.info("Eşzamanlı duplicate rapor isteği DB seviyesinde engellendi: userId={}", userId);
                throw new ApiException(ResponseCode.DUPLICATE,
                        "Zaten işlenmekte olan bir rapor isteğiniz var. Lütfen tamamlanmasını bekleyin.");
            }
            throw ex;
        }
        UUID finalRequestId = saved.getRequestId();

        // V11: ücretsiz ilk rapor hakkı bu istekle tüketildi (bkz. FreeUsageService sınıf yorumu —
        // active_lock_key zaten yarışı engellediğinden burada ek atomiklik gerekmez)
        if (isFreeUsage) {
            freeUsageService.markFreeReportUsed(userId, finalRequestId);
        }

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
                    // Kuyruğa hiç basılamadıysa worker bu isteği asla işlemeyecek — active_lock_key
                    // kilidi de bırakılır (aksi halde kullanıcı yeni bir rapor isteği hiç oluşturamaz,
                    // bu istek admin requeue-stuck ile elle kurtarılana kadar kalıcı olarak kilitlenir).
                    jdbcTemplate.update(
                            "UPDATE report_request SET queue_error = ?, active_lock_key = NULL, updated_date = ? WHERE request_id = ?",
                            errorMsg, Timestamp.valueOf(LocalDateTime.now()), finalRequestId);
                    log.warn("TX commit sonrası kuyruğa basılamadı: requestId={}, hata={}", finalRequestId, errorMsg);
                }
            }
        });

        log.info("Rapor isteği oluşturuldu (TX commit bekleniyor): requestId={}, userId={}, tip={}",
                finalRequestId, userId, mode);
        return reportRequestMapper.toDto(saved);
    }

    /** Bir exception zincirinin en köklü (root cause) mesajını döndürür; hiçbiri yoksa null. */
    private String rootCauseMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
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
     * Seçilen rapor tipine göre kredi yeterlilik kontrolü döner.
     * Frontend bu bilgiyle "krediniz yetersiz" uyarısını ve eksik kredi miktarını gösterir.
     * Endpoint: POST /payment/balance-check
     */
    @Transactional(readOnly = true)
    public BalanceCheckResponse checkBalance(UUID userId, String reportType) {
        AnalysisMode mode = parseAnalysisMode(reportType);
        int requiredCredits = reportPriceResolver.creditCostFor(mode);
        long creditBalance = paymentService.getCreditBalance(userId);
        boolean sufficient = creditBalance >= requiredCredits;
        BalanceCheckResponse resp = new BalanceCheckResponse();
        resp.setCreditBalance(creditBalance);
        resp.setRequiredCredits(requiredCredits);
        resp.setSufficient(sufficient);
        resp.setMissingCredits(sufficient ? 0 : requiredCredits - creditBalance);
        return resp;
    }

    /**
     * Kullanıcının rapor isteklerini sayfalı listeler (en yeni önce).
     * report tablosuyla LEFT JOIN: tamamlanan isteklerde report_id de döner; frontend
     * bu alanı göz ikonu ile raporu açmak için kullanır.
     * Endpoint: POST /report-request/list
     */
    @Transactional(readOnly = true)
    public List<ReportRequestDto> listRequests(UUID userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = (size > 0) ? size : 10;
        int offset = safePage * safeSize;

        // Korelasyonlu alt sorgu: henüz rapor oluşmamış isteklerde report_id NULL döner.
        // Düz LEFT JOIN report YERİNE kullanılıyor çünkü ensureReport() senkronizasyonsuz
        // (önce SELECT, yoksa INSERT) — eşzamanlı iki çağrı teorik olarak aynı request_id için
        // birden fazla report satırı oluşturabilir (E7 çift-tık bug'ıyla aynı desen). Düz JOIN bu
        // durumda satırı çoğaltır/yanlış satırı getirebilir; bu alt sorgu her zaman en güncel
        // (created_date DESC) raporu seçer. (LATERAL JOIN denendi, H2 desteklemediği için scalar
        // subquery'e geçildi — Postgres + H2 ikisinde de çalışır.)
        // V10: sector/subsector (rr üzerinde donmuş anlık kopya) — sektör/alt sektör kullanıcı
        // tarafından yeniden adlandırılamadığından (yalnızca admin migration'ları değiştirir) id
        // üzerinden canlı join güvenlidir. V12: own_account_name ARTIK rr üzerinde STRING olarak
        // donmuş — user_social_account'a canlı JOIN YAPILMAZ (AccountService hesabı yerinde
        // yeniden adlandırabildiğinden, canlı join eski raporların hesap adını değiştirirdi).
        // Bu migration'lardan ÖNCEKİ raporlarda ilgili alanlar null döner (frontend "—" gösterir)
        // — geriye dönük bir taşıma yapılmadı (V8/V9 ile tutarlı).
        String sql = """
                SELECT rr.request_id, rr.user_id, rr.report_type,
                       rr.queue_pushed, rr.queue_push_date, rr.queue_error,
                       rr.status, rr.process_error, rr.process_started_date, rr.process_finished_date,
                       rr.created_date, rr.updated_date, rr.is_free_usage, rr.own_account_name,
                       (SELECT r2.report_id FROM report r2
                        WHERE r2.request_id = rr.request_id
                        ORDER BY r2.created_date DESC LIMIT 1) AS report_id,
                       sec.name AS sector_name, sub.name AS subsector_name
                FROM report_request rr
                LEFT JOIN sector sec ON sec.sector_id = rr.sector_id
                LEFT JOIN subsector sub ON sub.subsector_id = rr.subsector_id
                WHERE rr.user_id = ? AND rr.active = 1
                ORDER BY rr.created_date DESC
                LIMIT ? OFFSET ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            ReportRequestDto dto = new ReportRequestDto();
            dto.setRequestId(rs.getObject("request_id", UUID.class));
            dto.setUserId(rs.getObject("user_id", UUID.class));
            dto.setReportType(rs.getString("report_type"));
            dto.setQueuePushed(rs.getObject("queue_pushed", Integer.class));
            if (rs.getTimestamp("queue_push_date") != null) {
                dto.setQueuePushDate(rs.getTimestamp("queue_push_date").toLocalDateTime());
            }
            dto.setQueueError(rs.getString("queue_error"));
            dto.setStatus(rs.getString("status"));
            dto.setProcessError(rs.getString("process_error"));
            if (rs.getTimestamp("process_started_date") != null) {
                dto.setProcessStartedDate(rs.getTimestamp("process_started_date").toLocalDateTime());
            }
            if (rs.getTimestamp("process_finished_date") != null) {
                dto.setProcessFinishedDate(rs.getTimestamp("process_finished_date").toLocalDateTime());
            }
            if (rs.getTimestamp("created_date") != null) {
                dto.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
            }
            if (rs.getTimestamp("updated_date") != null) {
                dto.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
            }
            // LEFT JOIN sonucu: rapor tamamlandıysa dolu, henüz yoksa null
            dto.setReportId(rs.getObject("report_id", UUID.class));
            dto.setSectorName(rs.getString("sector_name"));
            dto.setSubsectorName(rs.getString("subsector_name"));
            dto.setOwnAccountName(rs.getString("own_account_name"));
            Integer freeUsage = rs.getObject("is_free_usage", Integer.class);
            dto.setFreeUsage(freeUsage != null && freeUsage == 1);
            return dto;
        }, userId, safeSize, offset);
    }

    /**
     * Rapor oluşturmanın kredi maliyetini, ücretsiz hak durumunu, bakiyeyi ve
     * oluşturulabilirlik (canCreate/blockReason) bilgisini tek nesnede döndürür.
     * Geliştirme 2: UI'da artık tip seçimi yok — mod backend'de otomatik belirlendiğinden
     * bu uç yalnızca "oluşturabilir miyim, ne kadara" bilgisini taşır; createRequest'teki
     * validasyon kurallarıyla BİREBİR aynı mantığı yansıtır.
     * Endpoint: POST /report-request/available-types
     */
    @Transactional(readOnly = true)
    public AvailableTypesResponseDto getAnalysisSelectability(UUID userId) {
        boolean hasOwn = findOwnAccountId(userId) != null;
        long creditBalance = paymentService.getCreditBalance(userId);
        // V11: ücretsiz hak varsa fiyat tipten bağımsız sabit olduğundan mod farketmeksizin true
        boolean freeAvailable = appProperties.getPayment().isEnabled() && freeUsageService.isFreeReportAvailable(userId);
        // Tüm modlarda tek fiyat (ReportPriceResolver) — hangi mod geçildiği sonucu değiştirmez
        int creditCost = reportPriceResolver.creditCostFor(AnalysisMode.OWN_ONLY);

        boolean canCreate = true;
        String blockReason = null;
        if (!hasOwn) {
            canCreate = false;
            blockReason = "Rapor oluşturmak için önce kendi hesabınızı eklemelisiniz.";
        } else if (!hasSectorSelected(userId)) {
            canCreate = false;
            blockReason = "Sektör analizi için önce sektör seçmelisiniz.";
        }

        return AvailableTypesResponseDto.builder()
                .creditCost(creditCost)
                .free(freeAvailable)
                .creditBalance(creditBalance)
                .canCreate(canCreate)
                .blockReason(blockReason)
                .build();
    }

    /**
     * Kredi paketi satın alma başlatır (FAZ CREDIT).
     * PayTR tarafında hiçbir şey değişmez — TL tutarlı ödeme alır; kredi eşlemesi callback SONRASI yapılır.
     * Endpoint: POST /payment/purchase
     */
    @Transactional
    public PurchaseResponse initiatePurchase(UUID userId, String packageCode, String clientIp) {
        CreditCatalog.CreditPackage pkg = CreditCatalog.findPackage(packageCode);
        if (pkg == null) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR, "Geçersiz packageCode: " + packageCode);
        }
        String merchantOid = generateMerchantOid();
        LocalDateTime exp = LocalDateTime.now().plusMinutes(30);
        paymentService.createInitiatedPurchase(userId, pkg, merchantOid, exp);
        String email = lookupEmail(userId);
        PaytrFormPayload payload = paytrGateway.buildPaymentForm(merchantOid, clientIp, email, pkg.priceTl());
        return new PurchaseResponse(merchantOid, payload);
    }

    /**
     * Satın alınabilir kredi paketlerini + kullanıcının güncel kredi bakiyesini döndürür.
     * Endpoint: POST /payment/packages
     */
    @Transactional(readOnly = true)
    public PackagesResponse getPackagesResponse(UUID userId) {
        List<PackageDto> packages = CreditCatalog.packages().stream()
                .map(p -> new PackageDto(p.code(), p.name(), p.priceTl(), p.credits(), p.featured()))
                .toList();
        return new PackagesResponse(packages, paymentService.getCreditBalance(userId));
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
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), 0L);
        }
        return new WalletDto(
                w.getBalance().setScale(2, RoundingMode.HALF_UP),
                w.getCurrency() != null ? w.getCurrency() : "TL",
                w.getTotalTopup().setScale(2, RoundingMode.HALF_UP),
                w.getTotalSpent().setScale(2, RoundingMode.HALF_UP),
                w.getCreditBalance() == null ? 0L : w.getCreditBalance());
    }

    // ============================================================
    // Yardımcı metodlar
    // ============================================================

    /**
     * Kullanıcının aktif kendi sosyal hesabının id'sini döndürür; yoksa null.
     */
    private UUID findOwnAccountId(UUID userId) {
        // ORDER BY updated_date DESC: D2 (tek hesap) kuralı ihlal edilip birden fazla aktif satır
        // kalmışsa en güncel hesap seçilir (bkz. AccountService/ContentRequestService aynı desen).
        String sql = """
                SELECT user_social_account_id
                FROM user_social_account
                WHERE user_id = ? AND active = 1
                ORDER BY updated_date DESC
                LIMIT 1
                """;
        List<UUID> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getObject("user_social_account_id", UUID.class),
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Kullanıcının O ANKİ sektör/alt sektör id'lerini döner ([sectorId, subsectorId]) — V10,
     * rapor oluşturma anında donmuş bir kopyayı report_request'e yazmak için.
     */
    private UUID[] lookupUserSectorSnapshot(UUID userId) {
        String sql = """
                SELECT sector_id, subsector_id
                FROM user_info
                WHERE user_id = ? AND active = 1
                """;
        List<UUID[]> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> new UUID[] {
                        rs.getObject("sector_id", UUID.class),
                        rs.getObject("subsector_id", UUID.class)
                }, userId);
        return rows.isEmpty() ? new UUID[] { null, null } : rows.get(0);
    }

    /**
     * Verilen hesabın o ANDAKİ account_name'ini getirir (V12 snapshot için). Bulunamazsa null.
     */
    private String lookupAccountName(UUID userSocialAccountId) {
        String sql = """
                SELECT account_name
                FROM user_social_account
                WHERE user_social_account_id = ?
                """;
        List<String> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getString("account_name"), userSocialAccountId);
        return rows.isEmpty() ? null : rows.get(0);
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
            // Locale.ROOT şart: Türkçe locale'de "own_only".toUpperCase() beklenmedik sonuç
            // üretebilir ve ASCII enum sabitiyle eşleşmez.
            return AnalysisMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResponseCode.VALIDATION_ERROR,
                    "Geçersiz reportType değeri: " + value + ". Geçerli değerler: NONE, OWN_ONLY");
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
