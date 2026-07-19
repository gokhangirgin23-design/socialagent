package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.config.AppProperties;
import com.api.dto.CreateReportRequestDto;
import com.api.dto.ReportRequestDto;
import com.api.dto.repository.ReportRequestRepository;
import com.api.entity.ReportRequest;
import com.api.mapper.ReportRequestMapper;
import com.api.messaging.JobQueueProducer;

/**
 * Geliştirme 2 — tek tip rapor + otomatik mod seçimi. Rakip hesap özelliğinin kaldırılmasıyla
 * mod artık her zaman OWN_ONLY'dir (hasMonitoredAccounts/BOTH kaldırıldı). Spring'siz birim
 * testi (broker/DB gerektirmez). reportType artık istekten OKUNMAZ; mod, kullanıcının kendi
 * hesap/sektör durumuna göre createRequest içinde otomatik belirlenir:
 *   - Kendi hesap yok  → VALIDATION_ERROR, hiçbir kayıt oluşturulmaz.
 *   - Kendi hesap var, sektör YOK → VALIDATION_ERROR.
 *   - Kendi hesap var, sektör var → mode = OWN_ONLY.
 *
 * jdbcTemplate.query çağrıları SQL metni içeriğine göre dallandırılır (stubJdbc) — aynı desen
 * V11 ücretsiz-hak testinde de kullanılıyordu.
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
        // testin odağı mod otomatik belirleme + duplicate koruması.
        appProperties.getPayment().setEnabled(false);
        freeUsageService = mock(FreeUsageService.class);

        service = new ReportRequestService(jdbcTemplate, reportRequestRepository, reportRequestMapper,
                jobQueueProducer, paymentService, paytrGateway, reportPriceResolver, appProperties, freeUsageService);
    }

    /**
     * jdbcTemplate.query'yi SQL içeriğine göre dallandırır — en spesifik kontrol önce.
     *
     * @param ownAccountId   null ise kullanıcının kendi hesabı yok
     * @param hasSector      sektör seçili mi
     * @param hasActiveReq   zaten PENDING/PROCESSING bir isteği var mı (E7 ön kontrol)
     */
    @SuppressWarnings("unchecked")
    private void stubJdbc(UUID ownAccountId, boolean hasSector, boolean hasActiveReq) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("subsector_id")) {
                        // lookupUserSectorSnapshot: UUID[] {sectorId, subsectorId}
                        return java.util.Collections.singletonList(new UUID[] { UUID.randomUUID(), UUID.randomUUID() });
                    }
                    if (sql.contains("status IN")) {
                        return hasActiveReq ? List.of(UUID.randomUUID()) : List.of();
                    }
                    if (sql.contains("account_name")) {
                        // lookupAccountName (V12 snapshot)
                        return List.of("test_hesap");
                    }
                    if (sql.contains("user_social_account_id")) {
                        return ownAccountId == null ? List.of() : List.of(ownAccountId);
                    }
                    if (sql.contains("sector_id")) {
                        return hasSector ? List.of(UUID.randomUUID()) : List.of();
                    }
                    return List.of();
                });
    }

    /** saveAndFlush'ı, servisin gerçekten inşa ettiği ReportRequest'i yakalayacak şekilde stub'lar. */
    private ArgumentCaptor<ReportRequest> stubSaveAndFlush() {
        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        when(reportRequestRepository.saveAndFlush(captor.capture())).thenAnswer(inv -> {
            ReportRequest r = captor.getValue();
            if (r.getRequestId() == null) {
                r.setRequestId(UUID.randomUUID());
            }
            return r;
        });
        when(reportRequestMapper.toDto(any(ReportRequest.class))).thenReturn(new ReportRequestDto());
        return captor;
    }

    private ReportRequestDto createWithTx(CreateReportRequestDto req) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            return service.createRequest(userId, req);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ============================================================
    // Geliştirme 2 — mod otomatik belirleme (her zaman OWN_ONLY)
    // ============================================================

    @Test
    void kendiHesabiOlmayanKullaniciRaporOlusturamaz() {
        stubJdbc(null, false, false);

        CreateReportRequestDto req = new CreateReportRequestDto();

        ApiException ex = assertThrows(ApiException.class, () -> service.createRequest(userId, req));
        assertEquals(ResponseCode.VALIDATION_ERROR, ex.getResponseCode());
        assertTrue(ex.getMessage().contains("kendi hesabınızı"));
        verify(reportRequestRepository, never()).saveAndFlush(any());
        verify(paymentService, never()).getCreditBalance(any());
    }

    @Test
    void kendiHesabiVeSektoruOlanKullaniciOWN_ONLYOlusturur() {
        UUID ownAccountId = UUID.randomUUID();
        stubJdbc(ownAccountId, true, false);
        ArgumentCaptor<ReportRequest> captor = stubSaveAndFlush();

        CreateReportRequestDto req = new CreateReportRequestDto();
        createWithTx(req);

        ReportRequest saved = captor.getValue();
        assertEquals("OWN_ONLY", saved.getReportType());
        assertEquals(ownAccountId, saved.getSelectedUserSocialAccountId());
    }

    @Test
    void kendiHesabiVarSektorYokValidationError() {
        UUID ownAccountId = UUID.randomUUID();
        stubJdbc(ownAccountId, false, false);

        CreateReportRequestDto req = new CreateReportRequestDto();

        ApiException ex = assertThrows(ApiException.class, () -> service.createRequest(userId, req));
        assertEquals(ResponseCode.VALIDATION_ERROR, ex.getResponseCode());
        assertTrue(ex.getMessage().contains("sektör"));
        verify(reportRequestRepository, never()).saveAndFlush(any());
    }

    @Test
    void eskiReportTypeAlaniGonderilseDeYokSayilirModYineOtomatikBelirlenir() {
        // B8 — eski istemci hâlâ reportType="COMPETITOR_ONLY" gibi artık geçersiz bir değer
        // gönderebilir; alan artık okunmaz, mod yine kurallara göre otomatik belirlenir.
        UUID ownAccountId = UUID.randomUUID();
        stubJdbc(ownAccountId, true, false);
        ArgumentCaptor<ReportRequest> captor = stubSaveAndFlush();

        CreateReportRequestDto req = new CreateReportRequestDto();
        req.setReportType("COMPETITOR_ONLY");
        createWithTx(req);

        assertEquals("OWN_ONLY", captor.getValue().getReportType());
    }

    // ============================================================
    // E7 — çift-tık/duplicate koruması (mod başarıyla belirlendikten SONRA devreye girer)
    // ============================================================

    @Test
    void zatenAktifIstegiOlanKullaniciYeniIstekOlusturamaz() {
        UUID ownAccountId = UUID.randomUUID();
        stubJdbc(ownAccountId, true, true);

        CreateReportRequestDto req = new CreateReportRequestDto();

        ApiException ex = assertThrows(ApiException.class, () -> service.createRequest(userId, req));
        assertEquals(ResponseCode.DUPLICATE, ex.getResponseCode());
        verify(reportRequestRepository, never()).saveAndFlush(any());
    }

    @Test
    void esZamanliCiftTikDbSeviyesindeEngellenir() {
        UUID ownAccountId = UUID.randomUUID();
        stubJdbc(ownAccountId, true, false);
        when(reportRequestRepository.saveAndFlush(any(ReportRequest.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_report_request_active_lock\""));

        CreateReportRequestDto req = new CreateReportRequestDto();

        ApiException ex = assertThrows(ApiException.class, () -> service.createRequest(userId, req));
        assertEquals(ResponseCode.DUPLICATE, ex.getResponseCode());
    }

    @Test
    void aktifIstekYokkenNormalOlusturmaBasarili() {
        UUID ownAccountId = UUID.randomUUID();
        stubJdbc(ownAccountId, true, false);
        stubSaveAndFlush();

        CreateReportRequestDto req = new CreateReportRequestDto();
        createWithTx(req);

        verify(reportRequestRepository).saveAndFlush(any(ReportRequest.class));
    }

    // ============================================================
    // V11 — Ücretsiz ilk kullanım hakkı
    // ============================================================

    @Test
    void ucretsizRaporHakkiVarsaKrediKontroluAtlanirVeHakTuketilir() {
        appProperties.getPayment().setEnabled(true); // kredi kapısı AÇIK — yine de ücretsiz hak devreye girmeli
        UUID ownAccountId = UUID.randomUUID();
        stubJdbc(ownAccountId, true, false);
        when(freeUsageService.isFreeReportAvailable(userId)).thenReturn(true);
        ArgumentCaptor<ReportRequest> captor = stubSaveAndFlush();

        CreateReportRequestDto req = new CreateReportRequestDto();
        ReportRequestDto result = createWithTx(req);

        assertEquals(Boolean.FALSE, result.getInsufficientCredits());
        assertEquals("OWN_ONLY", captor.getValue().getReportType());
        // Kredi bakiyesi HİÇ sorgulanmadı — ücretsiz hak kredi kontrolünün önüne geçti
        verify(paymentService, never()).getCreditBalance(any());
        verify(freeUsageService).markFreeReportUsed(eq(userId), eq(captor.getValue().getRequestId()));
    }
}
