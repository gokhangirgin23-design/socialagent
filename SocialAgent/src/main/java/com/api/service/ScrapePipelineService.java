package com.api.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyPost;
import com.api.config.AppProperties;
import com.api.entity.ReportRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Worker'ın çalıştırdığı scraping pipeline'ı (CLAUDE.md Bölüm 9, 10).
 * Scheduler yok; ReportRequestService oluşturulunca direkt kuyruğa basılır.
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Akış:
 *   1) report_request'i yükle (JdbcTemplate native).
 *   2) report_type'a göre hedefleri çöz (TargetResolver).
 *   3) Her hedef için tekrar-analiz koruması; gerekiyorsa Apify'dan son N gönderi çek.
 *   4) Gönderileri social_post'a yaz (save-or-update).
 *   5) Analiz edilmemiş gönderileri AI ile analiz et (AnalysisPipelineService → post_analysis).
 *   6) Analizlerden Markdown rapor üret (ReportPipelineService → report).
 *   7) Rapor hazırsa kullanıcıya bildirim gönder (NotificationService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScrapePipelineService {

    // Native request yükleme için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // Mod -> hedef hesap kümesi çözümleyici
    private final TargetResolver targetResolver;

    // Apify gönderi çekme istemcisi
    private final ApifyClient apifyClient;

    // social_post yazma + tekrar-analiz koruması
    private final SocialPostService socialPostService;

    // Scraping sonrası AI analizini çalıştıran pipeline
    private final AnalysisPipelineService analysisPipelineService;

    // Analizlerden Markdown rapor üreten pipeline
    private final ReportPipelineService reportPipelineService;

    // Rapor tamamlanınca bildirim (DB + mail + push)
    private final NotificationService notificationService;

    // Çekilecek son gönderi sayısı gibi ayarlar
    private final AppProperties appProperties;

    /**
     * Tek bir rapor isteğini baştan sona işler (worker bunu çağırır).
     * @Transactional YOK: Apify (~120s) ve AI (~60s) HTTP çağrıları burada tetikleniyor.
     * Alt servisler kendi kısa transaction'larını yönetir.
     *
     * @param requestId işlenecek rapor isteğinin id'si
     */
    public void processRequest(UUID requestId) {
        // 1) Rapor isteğini yükle (aktif olmalı)
        ReportRequest request = loadRequest(requestId);
        if (request == null) {
            log.warn("İşlenecek rapor isteği bulunamadı veya pasif: requestId={}", requestId);
            return;
        }

        // 2) Moda göre hedefleri çöz
        List<ScrapeTarget> targets = targetResolver.resolve(request);
        if (targets.isEmpty()) {
            log.info("Rapor isteği için hedef hesap çıkmadı: requestId={}, tip={}",
                    requestId, request.getReportType());
        } else {
            // Çekilecek gönderi sayısı (config; URL başına)
            int recentLimit = appProperties.getApify().getRecentPostsLimit();

            int totalInserted = 0;
            // 3) Her hedef için pipeline
            for (ScrapeTarget target : targets) {
                // Tekrar-analiz koruması: pencere içinde analiz edildiyse hem Apify hem AI atlanır
                if (socialPostService.isRecentlyAnalyzed(target)) {
                    continue;
                }
                // Apify'dan URL bazlı post çek
                List<ApifyPost> posts = apifyClient.fetchPostsByUrls(
                        List.of(target.url()), recentLimit);
                // social_post'a yaz (save-or-update); eklenen sayıyı topla
                totalInserted += socialPostService.saveRecentPosts(requestId, target, posts);
            }

            log.info("Scraping tamamlandı: requestId={}, hedef={}, toplamYeni={}",
                    requestId, targets.size(), totalInserted);
        }

        // 4) Analiz edilmemiş gönderileri AI ile analiz et (idempotent)
        int analyzed = analysisPipelineService.analyzeRequest(requestId);
        log.info("AI analizi tamamlandı: requestId={}, yazılanAnaliz={}", requestId, analyzed);

        // 5) Tüm post_analysis JSON'larını topla → OpenAI → Markdown rapor
        boolean reportDone = reportPipelineService.generateReport(requestId);
        log.info("Rapor üretimi: requestId={}, raporTamamlandi={}", requestId, reportDone);

        // 6) Rapor COMPLETED ise kullanıcıya bildirim (notification kaydı + mail + push).
        //    Bildirim adımı bağımsız tx'tir (REQUIRES_NEW); hata pipeline'ı bozmaz.
        if (reportDone) {
            try {
                notificationService.notifyReportCompleted(requestId);
            } catch (Exception ex) {
                log.warn("Bildirim gönderilemedi (pipeline etkilenmedi): requestId={}, hata={}",
                        requestId, ex.getMessage());
            }
        }
    }

    /**
     * report_request'i id ile yükler (yalnızca pipeline'ın ihtiyaç duyduğu kolonlar).
     * Bulunamazsa/pasifse null.
     */
    private ReportRequest loadRequest(UUID requestId) {
        String sql = """
                SELECT request_id, user_id, report_type, selected_user_social_account_id
                FROM report_request
                WHERE request_id = ? AND active = 1
                """;
        List<ReportRequest> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            ReportRequest r = new ReportRequest();
            r.setRequestId(rs.getObject("request_id", UUID.class));
            r.setUserId(rs.getObject("user_id", UUID.class));
            r.setReportType(rs.getString("report_type"));
            r.setSelectedUserSocialAccountId(rs.getObject("selected_user_social_account_id", UUID.class));
            return r;
        }, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
