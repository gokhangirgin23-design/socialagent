package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.api.common.ApiException;
import com.api.config.AppProperties;
import com.api.dto.ContentCreateRequest;
import com.api.dto.ContentCreateResponse;
import com.api.dto.repository.ContentRequestRepository;
import com.api.entity.ContentRequest;
import com.api.entity.ContentType;
import com.api.messaging.ContentQueueProducer;

/**
 * ContentRequestService için Spring'siz birim testi (DB/queue/S3 gerektirmez).
 * Doğrulanan davranışlar:
 *  - FAZ CREDIT Madde 1: includeTextInVisual=true istekte hata döner, false istekte akış devam eder.
 *  - ICERIK-RAPOR-AYRISTIRMA-SPEC.md §2.5: request'te reportId alanı yok, create() rapor servisine/
 *    tablosuna hiç sorgu atmıyor (jdbcTemplate.queryForObject hiç çağrılmıyor).
 *  - Kullanıcının bağlı hesabı varsa/yoksa create() bunu userId'den otomatik bulur
 *    (resolveOwnSocialAccountId) — istekten socialAccountId alınmaz, kullanıcıya sorulmaz.
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
    private final UUID socialAccountId = UUID.randomUUID();

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

    @Test
    void yazisizGorselIstegiNormalAkisaDevamEder() {
        ContentCreateRequest req = sampleRequest("POST", false);
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);
        when(contentRequestRepository.save(any(ContentRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ContentCreateResponse response = service.create(userId, req);

        assertEquals("QUEUED", response.getStatus());
        verify(contentQueueProducer).publish(any());
    }

    @Test
    void raporAlaniOlmayanIstekRaporServisineHicSorguAtmaz() {
        // Spec kabul kriteri #1/#4: reportId alanı DTO'da yok; create() rapor tablosuna/servisine
        // hiç sorgu atmaz (jdbcTemplate.queryForObject hiç çağrılmaz — yalnızca hesap otomatik
        // bulma sorgusu, queryForList, çalışır).
        ContentCreateRequest req = sampleRequest("POST", false);
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);
        when(contentRequestRepository.save(any(ContentRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.create(userId, req);

        verify(jdbcTemplate, never()).queryForObject(anyString(), any(Class.class), any(), any());
    }

    @Test
    void kullanicininBagliHesabiVarsaOtomatikOlarakContentRequestEBaglanir() {
        ContentCreateRequest req = sampleRequest("POST", false);
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(userId))).thenReturn(List.of(socialAccountId));
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);
        ArgumentCaptor<ContentRequest> savedCaptor = ArgumentCaptor.forClass(ContentRequest.class);
        when(contentRequestRepository.save(savedCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        ContentCreateResponse response = service.create(userId, req);

        assertEquals("QUEUED", response.getStatus());
        assertEquals(socialAccountId, savedCaptor.getValue().getSocialAccountId());
    }

    @Test
    void kullanicininBagliHesabiYoksaSocialAccountIdNullKaydedilir() {
        ContentCreateRequest req = sampleRequest("POST", false);
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(userId))).thenReturn(List.of());
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);
        ArgumentCaptor<ContentRequest> savedCaptor = ArgumentCaptor.forClass(ContentRequest.class);
        when(contentRequestRepository.save(savedCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        ContentCreateResponse response = service.create(userId, req);

        assertEquals("QUEUED", response.getStatus());
        assertEquals(null, savedCaptor.getValue().getSocialAccountId());
    }

    // ============================================================
    // BACKEND-TODO Sorun 2, madde 2.2 — ürün görseli boyut guard'ı + anlamlı hata
    // ============================================================

    @Test
    void cokBuyukUrunGorseliValidationErrorDoner() {
        ContentCreateRequest req = sampleRequest("POST", false);
        // 11 MB'lık (10 MB sınırının üstünde) rastgele bayt, base64 data-URL olarak
        byte[] oversized = new byte[11 * 1024 * 1024];
        req.setProductImageUrl("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(oversized));
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(userId, req));

        assertEquals("Ürün görseli çok büyük (en fazla 10 MB). Lütfen daha küçük bir görsel seçin.", ex.getMessage());
        verify(contentRequestRepository, never()).save(any());
        verify(contentQueueProducer, never()).publish(any());
    }

    @Test
    void bozukBase64UrunGorseliValidationErrorDoner() {
        ContentCreateRequest req = sampleRequest("POST", false);
        req.setProductImageUrl("data:image/jpeg;base64,!!! gecersiz base64 !!!");
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(userId, req));

        assertEquals("Ürün görseli bozuk veya okunamıyor. Lütfen görseli tekrar seçin.", ex.getMessage());
        verify(contentRequestRepository, never()).save(any());
    }

    @Test
    void virgulsuzDataUrlValidationErrorDoner() {
        ContentCreateRequest req = sampleRequest("POST", false);
        req.setProductImageUrl("data:image/jpeg;base64");
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(userId, req));

        assertEquals("Ürün görseli formatı geçersiz. Lütfen görseli tekrar seçin.", ex.getMessage());
        verify(contentRequestRepository, never()).save(any());
    }

    // ============================================================
    // V11 — Ücretsiz ilk kullanım hakkı
    // ============================================================

    @Test
    void ucretsizIcerikHakkiVarsaKrediKontroluAtlanir() {
        ContentCreateRequest req = sampleRequest("POST", false);
        when(freeUsageService.isFreeContentAvailable(eq(userId), any())).thenReturn(true);
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

    @Test
    void ucretsizHakYaristaKaybedilirseNormalKrediAkisinaDoner() {
        // isFreeContentAvailable true ama tryConsumeFreeContent (atomik UPDATE) yarışta kaybeder (false)
        ContentCreateRequest req = sampleRequest("POST", false);
        when(freeUsageService.isFreeContentAvailable(eq(userId), any())).thenReturn(true);
        when(freeUsageService.tryConsumeFreeContent(eq(userId), any())).thenReturn(false);
        when(paymentService.getCreditBalance(userId)).thenReturn(100L);
        when(contentRequestRepository.save(any(ContentRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ContentCreateResponse response = service.create(userId, req);

        assertEquals("QUEUED", response.getStatus());
        assertEquals(Boolean.FALSE, response.getFreeUsage());
        verify(paymentService).getCreditBalance(userId);
    }

    @Test
    void raporUretmemisKullaniciUcretsizPostUretebilir() {
        // Spec §2.4/§2.5: free içerik hakkı artık rapor varlığına şart koşulmuyor.
        // Bu senaryoda create() reportId'siz çağrılıyor ve free hak yine de kullanılabiliyor.
        ContentCreateRequest req = sampleRequest("POST", false);
        when(freeUsageService.isFreeContentAvailable(userId, ContentType.POST)).thenReturn(true);
        when(freeUsageService.tryConsumeFreeContent(eq(userId), any())).thenReturn(true);
        when(contentRequestRepository.save(any(ContentRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ContentCreateResponse response = service.create(userId, req);

        assertEquals(Boolean.TRUE, response.getFreeUsage());
    }

    // ============================================================
    // BACKEND-TODO Sorun 3, madde 3.3 — availableTypes/free kuralları
    // (davranış DEĞİŞTİRİLMEDİ, sadece regresyona karşı testle sabitlendi)
    // ============================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> findType(Map<String, Object> availableTypesResult, String type) {
        List<Map<String, Object>> types = (List<Map<String, Object>>) availableTypesResult.get("types");
        return types.stream().filter(t -> type.equals(t.get("type"))).findFirst().orElseThrow();
    }

    @Test
    void availableTypesFreeRaporVeHicSatinAlmaYokkenPostStoryUcretsizCarouselKilitli() {
        when(paymentService.getCreditBalance(userId)).thenReturn(0L);
        when(freeUsageService.isFreeContentAvailable(userId, ContentType.POST)).thenReturn(true);
        when(freeUsageService.isFreeContentAvailable(userId, ContentType.STORY)).thenReturn(true);
        when(freeUsageService.hasEverPurchased(userId)).thenReturn(false);

        Map<String, Object> result = service.availableTypes(userId);

        assertEquals(Boolean.TRUE, findType(result, "POST").get("free"));
        assertEquals(Boolean.TRUE, findType(result, "STORY").get("free"));
        Map<String, Object> carousel = findType(result, "CAROUSEL");
        assertEquals(Boolean.TRUE, carousel.get("disabled"));
        assertNotNull(carousel.get("disabledReason"));
    }

    @Test
    void availableTypesFreeIcerikTuketildiktenSonraPostVeStoryUcretliGorunur() {
        // free_content_used=1 senaryosu: FreeUsageService artık ikisi için de false döner.
        when(paymentService.getCreditBalance(userId)).thenReturn(50L);
        when(freeUsageService.isFreeContentAvailable(userId, ContentType.POST)).thenReturn(false);
        when(freeUsageService.isFreeContentAvailable(userId, ContentType.STORY)).thenReturn(false);
        when(freeUsageService.hasEverPurchased(userId)).thenReturn(false);

        Map<String, Object> result = service.availableTypes(userId);

        assertEquals(Boolean.FALSE, findType(result, "POST").get("free"));
        assertEquals(Boolean.FALSE, findType(result, "STORY").get("free"));
    }

    @Test
    void availableTypesDahaOnceSatinAlmisKullaniciCarouselKilitliDegil() {
        when(paymentService.getCreditBalance(userId)).thenReturn(50L);
        when(freeUsageService.hasEverPurchased(userId)).thenReturn(true);

        Map<String, Object> result = service.availableTypes(userId);

        assertEquals(Boolean.FALSE, findType(result, "CAROUSEL").get("disabled"));
    }

    @Test
    void createIleFreeIcerikZatenTuketilmisseIkinciUretimYetersizKredideInsufficientDoner() {
        // availableTypes'ta free=false görülen durumun create() tarafındaki karşılığı: free akışa
        // hiç girilmez (tryConsumeFreeContent çağrılmaz), doğrudan kredi kontrolüne düşer.
        ContentCreateRequest req = sampleRequest("POST", false);
        when(freeUsageService.isFreeContentAvailable(eq(userId), any())).thenReturn(false);
        when(paymentService.getCreditBalance(userId)).thenReturn(0L);

        ContentCreateResponse response = service.create(userId, req);

        assertEquals("INSUFFICIENT", response.getStatus());
        verify(freeUsageService, never()).tryConsumeFreeContent(any(), any());
    }

    @Test
    void createIleCarouselDenemesindeUcretsizHakDevreyeGirmezYetersizKredideInsufficientDoner() {
        // Ücretsiz hak SADECE 1 post veya 1 story — Carousel'e asla uygulanmaz (kural bozulmamalı,
        // bkz. FreeUsageService.isFreeContentAvailable'ın kendi CAROUSEL/REEL filtresi de ayrıca
        // FreeUsageServiceTest.carouselIcinUcretsizHakHicKullanilamaz'da doğrulanıyor).
        ContentCreateRequest req = sampleRequest("CAROUSEL", false);
        when(paymentService.getCreditBalance(userId)).thenReturn(0L);

        ContentCreateResponse response = service.create(userId, req);

        assertEquals("INSUFFICIENT", response.getStatus());
        verify(freeUsageService, never()).tryConsumeFreeContent(any(), any());
    }
}
