package com.api.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyPost;
import com.api.config.AppProperties;
import com.api.dto.repository.ReportRequestRepository;
import com.api.entity.ReportRequest;

/**
 * ScrapePipelineService için Spring'siz birim testi (broker/DB gerektirmez).
 * Doğrulanan davranışlar:
 *  - Hedef son N günde analiz EDİLMEMİŞSE: Apify'dan çekilir ve social_post'a yazılır.
 *  - Hedef son N günde analiz EDİLMİŞSE: tekrar-analiz koruması Apify + AI'ı atlar.
 */
class ScrapePipelineServiceTest {

    private JdbcTemplate jdbcTemplate;
    private TargetResolver targetResolver;
    private ApifyClient apifyClient;
    private SocialPostService socialPostService;
    private AnalysisPipelineService analysisPipelineService;
    private ReportPipelineService reportPipelineService;
    private NotificationService notificationService;
    private AppProperties appProperties;
    private ReportService reportService;
    private PaymentService paymentService;
    private ReportRequestRepository reportRequestRepository;
    private ScrapePipelineService pipeline;

    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        targetResolver = org.mockito.Mockito.mock(TargetResolver.class);
        apifyClient = org.mockito.Mockito.mock(ApifyClient.class);
        socialPostService = org.mockito.Mockito.mock(SocialPostService.class);
        analysisPipelineService = org.mockito.Mockito.mock(AnalysisPipelineService.class);
        reportPipelineService = org.mockito.Mockito.mock(ReportPipelineService.class);
        notificationService = org.mockito.Mockito.mock(NotificationService.class);
        appProperties = new AppProperties();
        reportService = org.mockito.Mockito.mock(ReportService.class);
        paymentService = org.mockito.Mockito.mock(PaymentService.class);
        reportRequestRepository = org.mockito.Mockito.mock(ReportRequestRepository.class);
        pipeline = new ScrapePipelineService(jdbcTemplate, targetResolver, apifyClient, socialPostService,
                analysisPipelineService, reportPipelineService, notificationService, appProperties,
                reportService, paymentService, null, reportRequestRepository);
    }

    @SuppressWarnings("unchecked")
    @Test
    void analizEdilmemisHedefApifydanCekilirVeYazilir() {
        // loadRequest -> aktif rapor isteği (JPA findById)
        when(reportRequestRepository.findById(requestId)).thenReturn(Optional.of(request()));
        // Mod çözümü -> tek SECTOR hedef
        ScrapeTarget target = ScrapeTarget.sector("INSTAGRAM", "https://www.instagram.com/sektor1/");
        when(targetResolver.resolve(any(ReportRequest.class))).thenReturn(List.of(target));
        // Tekrar-analiz koruması -> son N günde analiz yok
        when(socialPostService.isRecentlyAnalyzed(any(ScrapeTarget.class))).thenReturn(false);
        // Apify -> bir gönderi
        when(apifyClient.fetchPostsByUrls(any(List.class), anyInt())).thenReturn(List.of(samplePost("p1")));
        // Rapor COMPLETED -> bildirim tetiklenmeli
        when(reportPipelineService.generateReport(eq(requestId))).thenReturn(true);

        pipeline.processRequest(requestId);

        verify(apifyClient, times(1)).fetchPostsByUrls(any(List.class), anyInt());
        verify(socialPostService, times(1)).saveRecentPosts(eq(requestId), eq(target), any());
        verify(analysisPipelineService, times(1)).analyzeRequest(eq(requestId));
        verify(reportPipelineService, times(1)).generateReport(eq(requestId));
        verify(notificationService, times(1)).notifyReportCompleted(eq(requestId));
    }

    @SuppressWarnings("unchecked")
    @Test
    void yakinZamandaAnalizEdilenHedefApifyAtlar() {
        when(reportRequestRepository.findById(requestId)).thenReturn(Optional.of(request()));
        ScrapeTarget target = ScrapeTarget.sector("INSTAGRAM", "https://www.instagram.com/sektor1/");
        when(targetResolver.resolve(any(ReportRequest.class))).thenReturn(List.of(target));
        // Son N günde analiz edilmiş -> Apify atlanır
        when(socialPostService.isRecentlyAnalyzed(any(ScrapeTarget.class))).thenReturn(true);

        pipeline.processRequest(requestId);

        verify(apifyClient, never()).fetchPostsByUrls(any(List.class), anyInt());
        verify(socialPostService, never()).saveRecentPosts(any(), any(), any());
        verify(notificationService, never()).notifyReportCompleted(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void kendiHesapScrapingBosDonerseRaporFailedOlurSektorVerisiyleDevamEtmez() {
        // Gerçek vaka: OWN_ONLY raporunda kullanıcının kendi hesabından (bi_butik_originals)
        // hiç post çekilemedi (özel/yanlış hesap adı), ama pipeline sessizce SECTOR verisiyle
        // devam edip COMPLETED işaretledi — Brand DNA kullanıcının gerçek ürünüyle alakasız bir
        // kimliğe (rakip sektör hesaplarından "tesettür") kaydı. Artık bu durumda FAILED olmalı.
        UUID ownAccountId = UUID.randomUUID();
        when(reportRequestRepository.findById(requestId)).thenReturn(Optional.of(ownOnlyRequest(ownAccountId)));
        ScrapeTarget ownTarget = ScrapeTarget.own("INSTAGRAM", "bi_butik_originals", ownAccountId);
        when(targetResolver.resolve(any(ReportRequest.class))).thenReturn(List.of(ownTarget));
        when(socialPostService.isRecentlyAnalyzed(any(ScrapeTarget.class))).thenReturn(false);
        // Apify boş döner (hesap özel/yanlış/scrape hatası)
        when(apifyClient.fetchPostsByUrls(any(List.class), anyInt())).thenReturn(List.of());
        // countOwnPosts -> 0 (hiç KENDİ postu yazılmadı)
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), (Object[]) any())).thenReturn(0);

        pipeline.processRequest(requestId);

        // Rapor/analiz hiç üretilmemeli — sektör verisiyle sahte bir "kendi hesap" raporu oluşmasın
        verify(analysisPipelineService, never()).analyzeRequest(any());
        verify(reportPipelineService, never()).generateReport(any());
        verify(notificationService, never()).notifyReportCompleted(any());
        // FAILED olarak işaretlenmeli
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("UPDATE report_request"),
                eq("FAILED"), anyString(), any(), any(), eq(requestId));
    }

    // ============================================================
    // Geliştirme 2 — SECTOR hedefinde yeni sector-posts-limit kullanılır
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void sektorHedefindeYeniSectorPostsLimitKullanilir() {
        // AppProperties varsayılanı: sectorPostsLimit=3 (own=5, competitor=2'den ayrı bir alan) —
        // recentLimitFor artık SECTOR için eski recent-posts-limit (5) yerine bunu okumalı.
        when(reportRequestRepository.findById(requestId)).thenReturn(Optional.of(request()));
        ScrapeTarget sectorTarget = ScrapeTarget.sector("INSTAGRAM", "https://www.instagram.com/sektor_hesap/");
        when(targetResolver.resolve(any(ReportRequest.class))).thenReturn(List.of(sectorTarget));
        when(socialPostService.isRecentlyAnalyzed(any(ScrapeTarget.class))).thenReturn(false);
        when(apifyClient.fetchPostsByUrls(any(List.class), anyInt())).thenReturn(List.of(samplePost("p1")));
        when(reportPipelineService.generateReport(eq(requestId))).thenReturn(true);

        pipeline.processRequest(requestId);

        verify(apifyClient).fetchPostsByUrls(any(List.class), eq(3));
    }

    // ============================================================
    // V11 — Ücretsiz ilk kullanım: gerçek kredi düşümü ASLA denenmemeli
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void ucretsizRaporTamamlaninca_GercekKrediDusumuHicDenenmez() {
        // Regresyon: loadRequest() eskiden elle SELECT kolon listesi kullanıyordu ve is_free_usage'ı
        // hiç seçmiyordu — bu yüzden ücretsiz raporlar bile GERÇEKTEN kredi düşürüyordu (canlıda
        // yaşandı, bkz. ScrapePipelineService yorumu). Artık JPA findById kullanıldığından bu test
        // bu bug sınıfının bir daha yaşanmayacağını doğruluyor.
        ReportRequest freeRequest = request();
        freeRequest.setIsFreeUsage(1);
        when(reportRequestRepository.findById(requestId)).thenReturn(Optional.of(freeRequest));

        ScrapeTarget target = ScrapeTarget.sector("INSTAGRAM", "https://www.instagram.com/sektor1/");
        when(targetResolver.resolve(any(ReportRequest.class))).thenReturn(List.of(target));
        when(socialPostService.isRecentlyAnalyzed(any(ScrapeTarget.class))).thenReturn(false);
        when(apifyClient.fetchPostsByUrls(any(List.class), anyInt())).thenReturn(List.of(samplePost("p1")));
        when(reportPipelineService.generateReport(eq(requestId))).thenReturn(true);

        pipeline.processRequest(requestId);

        verify(paymentService, never()).tryDebitCredits(any(), anyInt(), anyString(), any());
        // Yine de credit_debited=1 olarak işaretlenmeli (reconciliation'ın sonsuza kadar
        // "başarısız düşüm" sanıp tekrar tekrar denemesin diye)
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("credit_debited"),
                eq(1), org.mockito.ArgumentMatchers.isNull(), any(), eq(requestId));
    }

    private ReportRequest request() {
        ReportRequest r = new ReportRequest();
        r.setRequestId(requestId);
        r.setUserId(UUID.randomUUID());
        r.setReportType("NONE");
        r.setActive(1);
        return r;
    }

    private ReportRequest ownOnlyRequest(UUID selectedAccountId) {
        ReportRequest r = new ReportRequest();
        r.setRequestId(requestId);
        r.setUserId(UUID.randomUUID());
        r.setReportType("OWN_ONLY");
        r.setSelectedUserSocialAccountId(selectedAccountId);
        r.setActive(1);
        return r;
    }

    private ApifyPost samplePost(String postId) {
        return new ApifyPost(
                postId,
                "rakip1",
                "https://instagram.com/p/" + postId,
                "örnek caption",
                "#a #b",
                "https://cdn/img.jpg",
                "IMAGE",
                100L, 10L, 0L, 0L,
                LocalDateTime.now(),
                "{\"id\":\"" + postId + "\",\"likesCount\":100}");
    }
}
