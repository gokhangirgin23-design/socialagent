package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.api.common.ApiException;
import com.api.config.AppProperties;
import com.api.dto.ContentCreateRequest;
import com.api.dto.ContentCreateResponse;
import com.api.dto.repository.ContentRequestRepository;
import com.api.entity.ContentRequest;
import com.api.messaging.ContentQueueProducer;

/**
 * ContentRequestService için Spring'siz birim testi (DB/queue/S3 gerektirmez).
 * Doğrulanan davranış (FAZ CREDIT — Madde 1): includeTextInVisual=true istekte hata döner,
 * false istekte akış normal devam eder.
 */
class ContentRequestServiceTest {

    private ContentRequestRepository contentRequestRepository;
    private ContentQueueProducer contentQueueProducer;
    private JdbcTemplate jdbcTemplate;
    private AppProperties appProperties;
    private PaymentService paymentService;
    private S3UploadService s3UploadService;
    private FreeUsageService freeUsageService;
    private ContentRequestService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        contentRequestRepository = mock(ContentRequestRepository.class);
        contentQueueProducer = mock(ContentQueueProducer.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        appProperties = new AppProperties();
        paymentService = mock(PaymentService.class);
        s3UploadService = mock(S3UploadService.class);
        freeUsageService = mock(FreeUsageService.class);
        service = new ContentRequestService(contentRequestRepository, contentQueueProducer, jdbcTemplate,
                appProperties, paymentService, s3UploadService, freeUsageService);
    }

    private ContentCreateRequest sampleRequest(String contentType, boolean includeText) {
        ContentCreateRequest req = new ContentCreateRequest();
        req.setReportId(reportId);
        req.setContentType(contentType);
        req.setIncludeTextInVisual(includeText);
        return req;
    }

    @Test
    void yaziIcerenGorselIstegiReddedilir() {
        ContentCreateRequest req = sampleRequest("POST", true);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(userId, req));

        assertEquals("Görsel üzerine yazı ekleme özelliği şu an kullanılamıyor.", ex.getMessage());
        verify(contentRequestRepository, never()).save(any());
        verify(contentQueueProducer, never()).publish(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void yazisizGorselIstegiNormalAkisaDevamEder() {
        ContentCreateRequest req = sampleRequest("POST", false);
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);
        // Rapor sahiplik kontrolü: kullanıcıya ait rapor bulunuyor
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1);
        when(contentRequestRepository.save(any(ContentRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ContentCreateResponse response = service.create(userId, req);

        assertEquals("QUEUED", response.getStatus());
        verify(contentQueueProducer).publish(any());
    }

    // ============================================================
    // V11 — Ücretsiz ilk kullanım hakkı
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void ucretsizIcerikHakkiVarsaKrediKontroluAtlanir() {
        ContentCreateRequest req = sampleRequest("POST", false);
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1);
        when(freeUsageService.isFreeContentAvailable(eq(userId), eq(reportId), any())).thenReturn(true);
        when(freeUsageService.tryConsumeFreeContent(eq(userId), any())).thenReturn(true);
        when(contentRequestRepository.save(any(ContentRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ContentCreateResponse response = service.create(userId, req);

        assertEquals("QUEUED", response.getStatus());
        assertEquals(0, response.getCreditCost());
        assertEquals(Boolean.TRUE, response.getFreeUsage());
        // Kredi bakiyesi HİÇ sorgulanmadı — ücretsiz hak kredi kontrolünün önüne geçti
        verify(paymentService, never()).getCreditBalance(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void ucretsizHakYaristaKaybedilirseNormalKrediAkisinaDoner() {
        // isFreeContentAvailable true ama tryConsumeFreeContent (atomik UPDATE) yarışta kaybeder (false)
        ContentCreateRequest req = sampleRequest("POST", false);
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1);
        when(freeUsageService.isFreeContentAvailable(eq(userId), eq(reportId), any())).thenReturn(true);
        when(freeUsageService.tryConsumeFreeContent(eq(userId), any())).thenReturn(false);
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);
        when(contentRequestRepository.save(any(ContentRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ContentCreateResponse response = service.create(userId, req);

        assertEquals("QUEUED", response.getStatus());
        assertEquals(Boolean.FALSE, response.getFreeUsage());
        verify(paymentService).getCreditBalance(userId);
    }
}
