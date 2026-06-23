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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyPost;
import com.api.config.AppProperties;
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
        pipeline = new ScrapePipelineService(jdbcTemplate, targetResolver, apifyClient, socialPostService,
                analysisPipelineService, reportPipelineService, notificationService, appProperties,
                reportService, paymentService);
    }

    @SuppressWarnings("unchecked")
    @Test
    void analizEdilmemisHedefApifydanCekilirVeYazilir() {
        // loadRequest -> aktif rapor isteği
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
                .thenReturn(List.of(request()));
        // Mod çözümü -> tek MONITORED hedef
        ScrapeTarget target = ScrapeTarget.monitored("INSTAGRAM", "rakip1", UUID.randomUUID());
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
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
                .thenReturn(List.of(request()));
        ScrapeTarget target = ScrapeTarget.monitored("INSTAGRAM", "rakip1", UUID.randomUUID());
        when(targetResolver.resolve(any(ReportRequest.class))).thenReturn(List.of(target));
        // Son N günde analiz edilmiş -> Apify atlanır
        when(socialPostService.isRecentlyAnalyzed(any(ScrapeTarget.class))).thenReturn(true);

        pipeline.processRequest(requestId);

        verify(apifyClient, never()).fetchPostsByUrls(any(List.class), anyInt());
        verify(socialPostService, never()).saveRecentPosts(any(), any(), any());
        verify(notificationService, never()).notifyReportCompleted(any());
    }

    private ReportRequest request() {
        ReportRequest r = new ReportRequest();
        r.setRequestId(requestId);
        r.setUserId(UUID.randomUUID());
        r.setReportType("COMPETITOR_ONLY");
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
