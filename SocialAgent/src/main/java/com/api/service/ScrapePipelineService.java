package com.api.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
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
 *
 * İş (job) yaşam döngüsü (V2): işin başında PROCESSING, sonunda COMPLETED | PARTIAL | FAILED.
 * Bu güncellemeler bağımsız auto-commit'tir (bu sınıf @Transactional DEĞİL); böylece bir alt
 * adım rollback olsa bile status kaybolmaz ve sweep/requeue doğru çalışır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScrapePipelineService {

    // Native request yükleme + status güncelleme için JdbcTemplate
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

        // İşleme başladı (PENDING -> PROCESSING)
        markProcessing(requestId);
        try {
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

            if (reportDone) {
                // 6a) Eksik analiz var mı? Yutulmuş dış-API hatası bazı postlarda analiz bırakmamış olabilir.
                int total = countPosts(requestId);
                int done = countAnalyzedPosts(requestId);
                if (total > 0 && done < total) {
                    // Rapor üretildi ama eksik → PARTIAL (sweep/requeue adayı)
                    markFinished(requestId, "PARTIAL",
                            "Eksik analiz: " + done + "/" + total + " post analiz edildi");
                    log.warn("Rapor PARTIAL: requestId={}, analiz={}/{}", requestId, done, total);
                } else {
                    // Tam → COMPLETED
                    markFinished(requestId, "COMPLETED", null);
                }

                // 6b) Bildirim (bağımsız tx; hatası pipeline'ı/durumu bozmaz)
                try {
                    notificationService.notifyReportCompleted(requestId);
                } catch (Exception ex) {
                    log.warn("Bildirim gönderilemedi (pipeline etkilenmedi): requestId={}, hata={}",
                            requestId, ex.getMessage());
                }
            } else {
                // Akış patlamadı ama kullanılabilir rapor üretilemedi (ör. veri yok / generateReport=false)
                markFinished(requestId, "FAILED", "Rapor üretilemedi (generateReport=false)");
                log.warn("Rapor üretilemedi (FAILED): requestId={}", requestId);
            }
        } catch (Exception ex) {
            // Beklenmedik hata (DB/altyapı/kod) — exception kaçtı → FAILED
            // NOT: Apify/OpenAI/Gemini timeout'ları alt servislerde yutulur; buraya GELMEZ.
            //      Buraya yalnız infra/kod arızası ya da analyzeRequest @Transactional rollback'i kaçar.
            String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            markFinished(requestId, "FAILED", errMsg);
            log.error("İşleme hatası (FAILED): requestId={}, hata={}", requestId, errMsg, ex);
            // JobWorker üst seviyede yine ack eder; gerçek retry admin POST /admin/requeue-stuck ile elle tetiklenir.
        }
    }

    // ============================================================
    // İş (job) yaşam döngüsü yardımcıları — report_request.status
    // ============================================================

    /**
     * İşleme başlangıcı: PROCESSING + process_started_date.
     * Bağımsız auto-commit (processRequest @Transactional değil).
     */
    private void markProcessing(UUID requestId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update("""
                UPDATE report_request
                   SET status = 'PROCESSING', process_started_date = ?, updated_date = ?
                 WHERE request_id = ?
                """, now, now, requestId);
    }

    /**
     * Terminal durum: COMPLETED | PARTIAL | FAILED + process_finished_date + (varsa) process_error.
     */
    private void markFinished(UUID requestId, String status, String error) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update("""
                UPDATE report_request
                   SET status = ?, process_error = ?, process_finished_date = ?, updated_date = ?
                 WHERE request_id = ?
                """, status, error, now, now, requestId);
    }

    /** İsteğe ait toplam social_post sayısı. */
    private int countPosts(UUID requestId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM social_post WHERE request_id = ?", Integer.class, requestId);
        return n != null ? n : 0;
    }

    /** İsteğe ait, post_analysis satırı OLAN (analiz edilmiş) post sayısı. */
    private int countAnalyzedPosts(UUID requestId) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM social_post sp
                WHERE sp.request_id = ?
                  AND EXISTS (SELECT 1 FROM post_analysis pa
                              WHERE pa.social_post_id = sp.social_post_id)
                """, Integer.class, requestId);
        return n != null ? n : 0;
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
