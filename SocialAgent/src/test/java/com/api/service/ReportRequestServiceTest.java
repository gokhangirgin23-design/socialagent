package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.config.AppProperties;
import com.api.dto.CreateReportRequestDto;
import com.api.dto.repository.ReportRequestRepository;
import com.api.entity.ReportRequest;
import com.api.mapper.ReportRequestMapper;
import com.api.messaging.JobQueueProducer;

/**
 * E7 fix — çift-tık/duplicate koruması. Spring'siz birim testi (broker/DB gerektirmez).
 * Doğrulanan davranışlar:
 *  - Kullanıcının zaten PENDING/PROCESSING bir isteği varsa yeni istek DUPLICATE ile reddedilir
 *    (ön kontrol — saveAndFlush hiç çağrılmaz).
 *  - Ön kontrolü geçen eşzamanlı bir istek DB'nin active_lock_key UNIQUE constraint'ine çarparsa
 *    (DataIntegrityViolationException), bu da aynı şekilde DUPLICATE'e çevrilir.
 */
class ReportRequestServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ReportRequestRepository reportRequestRepository;
    private ReportRequestMapper reportRequestMapper;
    private JobQueueProducer jobQueueProducer;
    private PaymentService paymentService;
    private PaytrGateway paytrGateway;
    private ReportPriceResolver reportPriceResolver;
    private AppProperties appProperties;
    private FreeUsageService freeUsageService;
    private ReportRequestService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        reportRequestRepository = mock(ReportRequestRepository.class);
        reportRequestMapper = mock(ReportRequestMapper.class);
        jobQueueProducer = mock(JobQueueProducer.class);
        paymentService = mock(PaymentService.class);
        paytrGateway = mock(PaytrGateway.class);
        reportPriceResolver = mock(ReportPriceResolver.class);
        appProperties = new AppProperties();
        // Ödeme kapısını kapatarak kredi kontrolü akışını devre dışı bırakıyoruz;
        // testin odağı yalnızca duplicate koruması.
        appProperties.getPayment().setEnabled(false);
        freeUsageService = mock(FreeUsageService.class);

        service = new ReportRequestService(jdbcTemplate, reportRequestRepository, reportRequestMapper,
                jobQueueProducer, paymentService, paytrGateway, reportPriceResolver, appProperties, freeUsageService);

        // COMPETITOR_ONLY modu için ön koşul: en az 1 izlenen hesap var
        when(jdbcTemplate.query(contains("user_monitored_account_id"), any(RowMapper.class), (Object[]) any()))
                .thenReturn(List.of(UUID.randomUUID()));
    }

    @SuppressWarnings("unchecked")
    @Test
    void zatenAktifIstegiOlanKullaniciYeniIstekOlusturamaz() {
        // Ön kontrol: kullanıcının zaten PENDING/PROCESSING bir isteği var
        when(jdbcTemplate.query(contains("status IN"), any(RowMapper.class), (Object[]) any()))
                .thenReturn(List.of(UUID.randomUUID()));

        CreateReportRequestDto req = new CreateReportRequestDto();
        req.setReportType("COMPETITOR_ONLY");

        ApiException ex = assertThrows(ApiException.class, () -> service.createRequest(userId, req));
        assertEquals(ResponseCode.DUPLICATE, ex.getResponseCode());
        verify(reportRequestRepository, never()).saveAndFlush(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void esZamanliCiftTikDbSeviyesindeEngellenir() {
        // Ön kontrol geçilir (henüz aktif kayıt yok) — ama saveAndFlush eşzamanlı diğer istekle
        // active_lock_key UNIQUE constraint'ine çarpar.
        when(jdbcTemplate.query(contains("status IN"), any(RowMapper.class), (Object[]) any()))
                .thenReturn(List.of());
        when(reportRequestRepository.saveAndFlush(any(ReportRequest.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_report_request_active_lock\""));

        CreateReportRequestDto req = new CreateReportRequestDto();
        req.setReportType("COMPETITOR_ONLY");

        ApiException ex = assertThrows(ApiException.class, () -> service.createRequest(userId, req));
        assertEquals(ResponseCode.DUPLICATE, ex.getResponseCode());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aktifIstekYokkenNormalOlusturmaBasarili() {
        when(jdbcTemplate.query(contains("status IN"), any(RowMapper.class), (Object[]) any()))
                .thenReturn(List.of());
        ReportRequest saved = new ReportRequest();
        saved.setRequestId(UUID.randomUUID());
        saved.setUserId(userId);
        saved.setReportType("COMPETITOR_ONLY");
        when(reportRequestRepository.saveAndFlush(any(ReportRequest.class))).thenReturn(saved);
        when(reportRequestMapper.toDto(saved)).thenReturn(new com.api.dto.ReportRequestDto());

        CreateReportRequestDto req = new CreateReportRequestDto();
        req.setReportType("COMPETITOR_ONLY");

        // persistAndQueue, TX commit sonrası kuyruğa basmak için TransactionSynchronizationManager
        // kullanıyor; gerçek bir @Transactional proxy olmadan bunu manuel başlatmamız gerekiyor.
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createRequest(userId, req);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(reportRequestRepository).saveAndFlush(any(ReportRequest.class));
    }

    // ============================================================
    // V11 — Ücretsiz ilk kullanım hakkı
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void ucretsizRaporHakkiVarsaKrediKontroluAtlanirVeHakTuketilir() {
        appProperties.getPayment().setEnabled(true); // kredi kapısı AÇIK — yine de ücretsiz hak devreye girmeli
        when(jdbcTemplate.query(contains("status IN"), any(RowMapper.class), (Object[]) any()))
                .thenReturn(List.of());
        when(freeUsageService.isFreeReportAvailable(userId)).thenReturn(true);

        ReportRequest saved = new ReportRequest();
        saved.setRequestId(UUID.randomUUID());
        saved.setUserId(userId);
        saved.setReportType("NONE");
        when(reportRequestRepository.saveAndFlush(any(ReportRequest.class))).thenReturn(saved);
        when(reportRequestMapper.toDto(saved)).thenReturn(new com.api.dto.ReportRequestDto());
        // sektör sorguları: hasSectorSelected (yalnızca sector_id, ön koşul) ve
        // lookupUserSectorSnapshot (sector_id + subsector_id, UUID[] döner) — SQL içeriğine göre
        // ayrı dönüş tipleri gerektiğinden thenAnswer ile SQL metnine bakılarak dallanır.
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("subsector_id")) {
                        // DİKKAT: List.of(UUID[]) varargs spread'e uğrar (List<UUID> olur, List<UUID[]>
                        // DEĞİL) — singletonList tek sabit parametre aldığından array'i TEK eleman olarak sarar.
                        return java.util.Collections.singletonList(new UUID[] { UUID.randomUUID(), UUID.randomUUID() });
                    }
                    if (sql.contains("status IN")) {
                        return List.of();
                    }
                    if (sql.contains("sector_id")) {
                        return List.of(UUID.randomUUID());
                    }
                    return List.of();
                });

        CreateReportRequestDto req = new CreateReportRequestDto();
        req.setReportType("NONE");

        TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.createRequest(userId, req);
            assertEquals(Boolean.FALSE, result.getInsufficientCredits());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // Kredi bakiyesi HİÇ sorgulanmadı — ücretsiz hak kredi kontrolünün önüne geçti
        verify(paymentService, never()).getCreditBalance(any());
        verify(freeUsageService).markFreeReportUsed(eq(userId), eq(saved.getRequestId()));
    }
}
