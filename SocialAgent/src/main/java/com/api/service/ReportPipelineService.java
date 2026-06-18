package com.api.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.ai.AiAnalysisService;
import com.api.ai.prompt.ReportPrompts;
import com.api.dto.AccountReportRow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rapor üretim pipeline'ı (CLAUDE.md Bölüm 11).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Veri akışı (token verimliliği):
 *   social_post → SQL aggregate (avg likes, içerik tipi dağılımı vb.)  ─┐
 *   post_analysis → SQL (analysis_json listesi)                          ├→ Java hesap özeti
 *   analysis_json → Java-side JSON parse (isReel, hasHuman, vb.)        ─┘
 *   → OpenAI'ya hesap başına tek satır gönderilir
 *   → report.report_content (Markdown) — sadece YAZILIR, hiç okunmaz
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportPipelineService {

    // Sorgular için JdbcTemplate native
    private final JdbcTemplate jdbcTemplate;

    // OpenAI ile Markdown rapor üretimi
    private final AiAnalysisService aiAnalysisService;

    // report yazma + durum geçişleri
    private final ReportService reportService;

    // analysis_json parse için (thread-safe)
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Bir rapor isteğinin hesap bazlı özetlerinden Markdown rapor üretir ve report'a yazar.
     *
     * @param requestId raporlanacak rapor isteği
     * @return rapor COMPLETED olduysa true
     */
    @Transactional
    public boolean generateReport(UUID requestId) {
        // 1) report_type'ı yükle (karşılaştırmalı mı, başarı faktörü mü)
        String reportType = loadReportType(requestId);

        // 2) Hesap bazlı özetleri topla (SQL aggregate + Java JSON parse)
        List<AccountReportRow> summaries = loadAccountSummaries(requestId);
        if (summaries.isEmpty()) {
            log.info("Rapor için özet bulunamadı, rapor üretilmedi: requestId={}", requestId);
            return false;
        }

        // 3) report kaydını garanti et ve GENERATING'e geçir
        UUID reportId = reportService.ensureReport(requestId);
        reportService.markGenerating(reportId);

        // 4) Prompt üret ve OpenAI'dan Markdown iste
        String prompt = ReportPrompts.forJob(summaries, reportType);
        String markdown = aiAnalysisService.generateReport(prompt);

        // 5) Sonuca göre durum geçişi
        if (markdown == null || markdown.isBlank()) {
            reportService.markFailed(reportId);
            log.warn("Rapor üretilemedi (AI yok/boş), FAILED: requestId={}, reportId={}", requestId, reportId);
            return false;
        }

        reportService.markCompleted(reportId, markdown);
        log.info("Rapor üretildi (COMPLETED): requestId={}, reportId={}, hesapSayisi={}",
                requestId, reportId, summaries.size());
        return true;
    }

    // ============================================================
    // Veri yükleme
    // ============================================================

    /**
     * Rapor isteğinin report_type değerini yükler; bulunamazsa "NONE" döner.
     */
    private String loadReportType(UUID requestId) {
        String sql = "SELECT report_type FROM report_request WHERE request_id = ?";
        List<String> types = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("report_type"), requestId);
        return types.isEmpty() ? "NONE" : types.get(0);
    }

    /**
     * Hesap bazlı özetleri iki SQL sorgusu + Java aggregate ile üretir.
     * OWN + SECTOR ve MONITORED ayrı sorgularla çekilir; Java'da birleştirilir.
     */
    private List<AccountReportRow> loadAccountSummaries(UUID requestId) {
        List<PostRaw> rawRows = new ArrayList<>();
        rawRows.addAll(loadOwnAndSectorPosts(requestId));
        rawRows.addAll(loadMonitoredPosts(requestId));

        if (rawRows.isEmpty()) {
            return List.of();
        }

        // Hesap bazında grupla: "TİP:hesap_adi" → post listesi
        Map<String, List<PostRaw>> byAccount = new LinkedHashMap<>();
        for (PostRaw row : rawRows) {
            String key = row.source() + ":" + row.accountName();
            byAccount.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // Her hesap grubunu tek özet satıra dönüştür
        List<AccountReportRow> summaries = new ArrayList<>();
        for (List<PostRaw> posts : byAccount.values()) {
            summaries.add(aggregate(posts));
        }
        return summaries;
    }

    /**
     * OWN + SECTOR kaynak postların post + analiz verilerini çeker.
     * İki tablo eski stil "=" join (CLAUDE.md Madde 6).
     */
    private List<PostRaw> loadOwnAndSectorPosts(UUID requestId) {
        String sql = """
                SELECT
                    CASE WHEN sp.source_type = 'SECTOR' THEN 'SEKTÖR'
                         ELSE 'KENDİ HESABIN'
                    END AS kaynak,
                    COALESCE(sp.sector_account_name, 'kendi_hesap') AS hesap_adi,
                    sp.media_type,
                    sp.likes_count,
                    sp.comments_count,
                    sp.views_count,
                    pa.analysis_json
                FROM social_post sp, post_analysis pa
                WHERE sp.social_post_id = pa.social_post_id
                  AND sp.request_id = ?
                  AND sp.source_type IN ('OWN', 'SECTOR')
                ORDER BY hesap_adi
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PostRaw(
                rs.getString("kaynak"),
                rs.getString("hesap_adi"),
                rs.getString("media_type"),
                rs.getObject("likes_count", Long.class),
                rs.getObject("comments_count", Long.class),
                rs.getObject("views_count", Long.class),
                rs.getString("analysis_json")), requestId);
    }

    /**
     * MONITORED (rakip) hesapların post + analiz verilerini çeker.
     * monitored_account ile eski stil "=" join (CLAUDE.md Madde 6).
     */
    private List<PostRaw> loadMonitoredPosts(UUID requestId) {
        String sql = """
                SELECT
                    'RAKİP' AS kaynak,
                    ma.account_name AS hesap_adi,
                    sp.media_type,
                    sp.likes_count,
                    sp.comments_count,
                    sp.views_count,
                    pa.analysis_json
                FROM social_post sp, post_analysis pa, monitored_account ma
                WHERE sp.social_post_id = pa.social_post_id
                  AND sp.request_id = ?
                  AND sp.source_type = 'MONITORED'
                  AND sp.monitored_account_id = ma.monitored_account_id
                ORDER BY ma.account_name
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PostRaw(
                rs.getString("kaynak"),
                rs.getString("hesap_adi"),
                rs.getString("media_type"),
                rs.getObject("likes_count", Long.class),
                rs.getObject("comments_count", Long.class),
                rs.getObject("views_count", Long.class),
                rs.getString("analysis_json")), requestId);
    }

    // ============================================================
    // Java-side aggregate
    // ============================================================

    /**
     * Bir hesabın tüm post satırlarını tek AccountReportRow özetine dönüştürür.
     */
    private AccountReportRow aggregate(List<PostRaw> posts) {
        String source = posts.get(0).source();
        String accountName = posts.get(0).accountName();
        long postCount = posts.size();

        long sumLikes = 0, sumComments = 0, sumViews = 0;
        long imageCount = 0, videoCount = 0, carouselCount = 0;
        int reelCount = 0, humanCount = 0, modelCount = 0, productFocusedCount = 0;

        for (PostRaw p : posts) {
            sumLikes += nz(p.likesCount());
            sumComments += nz(p.commentsCount());
            sumViews += nz(p.viewsCount());

            if ("IMAGE".equalsIgnoreCase(p.mediaType())) imageCount++;
            else if ("VIDEO".equalsIgnoreCase(p.mediaType())) videoCount++;
            else if ("CAROUSEL".equalsIgnoreCase(p.mediaType())) carouselCount++;

            // Görsel metrikler: analysis_json → {"metrics":{"contentType":{"isReel":...}}, "visual":{...}}
            if (p.analysisJson() != null && !p.analysisJson().isBlank()) {
                try {
                    JsonNode root = MAPPER.readTree(p.analysisJson());
                    JsonNode isReelNode = root.path("metrics").path("contentType").path("isReel");
                    if (isReelNode.isBoolean() && isReelNode.asBoolean()) reelCount++;
                    JsonNode visual = root.path("visual");
                    if (boolVal(visual, "hasHuman")) humanCount++;
                    if (boolVal(visual, "hasModel")) modelCount++;
                    if (boolVal(visual, "isProductFocused")) productFocusedCount++;
                } catch (Exception ignored) {
                    // Bozuk JSON -> bu post atlanır, pipeline durmaz
                }
            }
        }

        return new AccountReportRow(
                source, accountName, postCount,
                postCount > 0 ? sumLikes / postCount : 0,
                postCount > 0 ? sumComments / postCount : 0,
                postCount > 0 ? sumViews / postCount : 0,
                imageCount, videoCount, carouselCount,
                reelCount, humanCount, modelCount, productFocusedCount);
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    private static long nz(Long v) {
        return v != null ? v : 0L;
    }

    private static boolean boolVal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isBoolean() && v.asBoolean();
    }

    /** SQL post satırı (iç kullanım) — hesap bazında gruplamak için taşıyıcı. */
    record PostRaw(
            String source,
            String accountName,
            String mediaType,
            Long likesCount,
            Long commentsCount,
            Long viewsCount,
            String analysisJson) {
    }
}
