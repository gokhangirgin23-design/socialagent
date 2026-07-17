package com.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.DashboardSummaryDto;
import com.api.dto.DashboardSummaryDto.AccountComparisonRow;
import com.api.dto.DashboardSummaryDto.AlertInfo;
import com.api.dto.DashboardSummaryDto.InsightInfo;
import com.api.dto.DashboardSummaryDto.LastReportInfo;
import com.api.dto.DashboardSummaryDto.WalletInfo;
import com.api.entity.UserPayment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dashboard özeti (POST /dashboard/summary) — tek çağrıda tüm veriler.
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 * Sorgular JdbcTemplate native + eski stil "=" join (CLAUDE.md Madde 6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbcTemplate;
    private final PaymentService paymentService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Dashboard için tüm özet verileri derler ve döndürür.
     */
    @Transactional(readOnly = true)
    public DashboardSummaryDto buildSummary(UUID userId) {
        // 1) Cüzdan
        WalletInfo wallet = buildWalletInfo(userId);

        // 2) Son tamamlanmış isteğin id'si (skor + insight için)
        UUID lastCompletedRequestId = findLastCompletedRequestId(userId);

        // 3) Hesap skoru (kendi postlar vs diğerleri)
        ScoreResult scoreResult = (lastCompletedRequestId != null)
                ? computeAccountScore(lastCompletedRequestId) : null;
        Integer accountScore = scoreResult != null ? scoreResult.score() : null;
        DashboardSummaryDto.ScoreBreakdown scoreBreakdown = scoreResult != null ? scoreResult.breakdown() : null;

        // 4) Son analiz tarihi (process_finished_date)
        java.time.LocalDateTime lastAnalysisDate = findLastAnalysisDate(userId);

        // 5) En son rapor isteği özeti
        LastReportInfo lastReport = findLastReport(userId);

        // 6) Structured insight JSON (en son COMPLETED/PARTIAL rapordan)
        InsightInfo latestInsight = findLatestInsight(userId);

        // 7) Uyarılar (etkileşim düşüşü, format trendi)
        List<AlertInfo> alerts = buildAlerts(userId, lastCompletedRequestId);

        // 8) Hesap bazlı kıyaslama verileri (dashboard tablo)
        List<AccountComparisonRow> accountComparison = (lastCompletedRequestId != null)
                ? buildAccountComparison(lastCompletedRequestId) : List.of();

        return DashboardSummaryDto.builder()
                .accountScore(accountScore)
                .scoreBreakdown(scoreBreakdown)
                .lastAnalysisDate(lastAnalysisDate)
                .lastReport(lastReport)
                .latestInsight(latestInsight)
                .alerts(alerts)
                .accountComparison(accountComparison)
                .wallet(wallet)
                .build();
    }

    // ── Cüzdan ──────────────────────────────────────────────────────────────

    private WalletInfo buildWalletInfo(UUID userId) {
        UserPayment w = paymentService.findWallet(userId);
        if (w == null) {
            return new WalletInfo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), "TL", 0L);
        }
        return new WalletInfo(
                w.getBalance().setScale(2, RoundingMode.HALF_UP),
                w.getCurrency() != null ? w.getCurrency() : "TL",
                w.getCreditBalance() == null ? 0L : w.getCreditBalance());
    }

    // ── Son tamamlanmış istek ───────────────────────────────────────────────

    private UUID findLastCompletedRequestId(UUID userId) {
        String sql = """
                SELECT request_id FROM report_request
                WHERE user_id = ? AND status IN ('COMPLETED', 'PARTIAL') AND active = 1
                ORDER BY created_date DESC LIMIT 1
                """;
        List<UUID> rows = jdbcTemplate.query(sql,
                (rs, i) -> rs.getObject("request_id", UUID.class), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Hesap skoru ─────────────────────────────────────────────────────────

    /** computeAccountScore'un dönüş taşıyıcısı: hem toplam skor hem "neden bu skor" kırılımı. */
    private record ScoreResult(int score, DashboardSummaryDto.ScoreBreakdown breakdown) {}

    /**
     * 0-100 kompozit hesap skoru — 3 bileşen: Göreli etkileşim (0-50, log2 ölçekli — büyüklük
     * farkını sıkıştırır), Paylaşım temposu (0-25, kendi gönderi sayısı/hedef 10), Etkileşim
     * trendi (0-25, kendi gönderilerin tarihe göre yeni yarısı vs eski yarısı). Eski ham oran
     * formülü ("ownAvg/othersAvg / 2 * 100") küçük hesaplarda oranı ~0.01'e çakıp skoru hep 1
     * gösteriyordu; kompozit skor takipçi büyüklüğü farkını göz ardı etmez ve motive edicidir.
     * Kendi gönderisi hiç yoksa null döner (0/1 gibi caydırıcı bir sayı göstermek yerine).
     */
    private ScoreResult computeAccountScore(UUID requestId) {
        long ownPostCount = queryOwnPostCount(requestId);
        if (ownPostCount == 0) {
            return null;
        }

        long ownAvg = queryAvgEngagement(requestId, new String[]{"OWN"});
        long othersAvg = queryAvgEngagement(requestId, new String[]{"SECTOR"});

        int relativePoints = computeRelativePoints(ownAvg, othersAvg);
        int activityPoints = computeActivityPoints(ownPostCount);
        int trendPoints = computeTrendPoints(requestId);

        int score = Math.max(0, Math.min(100, relativePoints + activityPoints + trendPoints));
        DashboardSummaryDto.ScoreBreakdown breakdown = new DashboardSummaryDto.ScoreBreakdown(
                relativePoints, activityPoints, trendPoints, ownPostCount, ownAvg, othersAvg);
        return new ScoreResult(score, breakdown);
    }

    /** Göreli etkileşim (0-50): eşit performans 25p, 4x iyi 50p, 4x kötü 0p (log2 ölçek). */
    private int computeRelativePoints(long ownAvg, long othersAvg) {
        if (ownAvg == 0) {
            return 0;
        }
        if (othersAvg == 0) {
            // Karşılaştırma için başka hesap yok; nötr orta değer
            return 25;
        }
        double ratio = (double) ownAvg / othersAvg;
        return (int) Math.round(clamp01(0.5 + 0.25 * log2(ratio)) * 50);
    }

    /** Paylaşım temposu (0-25): kendi gönderi sayısı / hedef 10, 10+ gönderi tam puan. */
    private int computeActivityPoints(long ownPostCount) {
        double normalized = Math.min(1.0, ownPostCount / 10.0);
        return (int) Math.round(normalized * 25);
    }

    /**
     * Etkileşim trendi (0-25): kendi gönderileri post_date'e göre eskiden yeniye sırala, ortadan
     * ikiye böl, yeni yarının ort. etkileşimini eski yarıyla kıyasla (aynı log2 ölçek). Yeterli
     * veri yoksa (2'den az post, ya da bir yarı tamamen 0 etkileşimliyse anlamlı kıyas yoksa)
     * nötr (~12p) döner — "yatay seyir" ile aynı görünsün diye bilerek caydırıcı olmayan bir
     * varsayılan seçildi.
     */
    private int computeTrendPoints(UUID requestId) {
        List<Long> engagements = queryOwnEngagementsByDate(requestId);
        if (engagements.size() < 2) {
            return 12;
        }
        int mid = engagements.size() / 2;
        double olderAvg = engagements.subList(0, mid).stream().mapToLong(Long::longValue).average().orElse(0);
        double newerAvg = engagements.subList(mid, engagements.size()).stream().mapToLong(Long::longValue).average().orElse(0);

        if (olderAvg == 0 && newerAvg == 0) {
            return 12;
        }
        if (newerAvg == 0) {
            return 0;
        }
        if (olderAvg == 0) {
            return 25;
        }
        double ratio = newerAvg / olderAvg;
        return (int) Math.round(clamp01(0.5 + 0.25 * log2(ratio)) * 25);
    }

    private double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Kendi gönderi sayısı (paylaşım temposu bileşeni için). */
    private long queryOwnPostCount(UUID requestId) {
        String sql = "SELECT COUNT(*) FROM social_post WHERE request_id = ? AND source_type = 'OWN'";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, requestId);
        return count != null ? count : 0;
    }

    /** Kendi gönderilerin etkileşimi (likes+comments), post_date'e göre eskiden yeniye sıralı. */
    private List<Long> queryOwnEngagementsByDate(UUID requestId) {
        String sql = """
                SELECT COALESCE(likes_count, 0) + COALESCE(comments_count, 0) AS engagement
                FROM social_post
                WHERE request_id = ? AND source_type = 'OWN'
                ORDER BY post_date ASC
                """;
        return jdbcTemplate.query(sql, (rs, i) -> rs.getLong("engagement"), requestId);
    }

    /** Belirtilen source_type'lar için ort. etkileşim (likes + comments). */
    private long queryAvgEngagement(UUID requestId, String[] sourceTypes) {
        if (sourceTypes.length == 0) {
            return 0;
        }
        // IN listesini manuel oluştur (CLAUDE.md: native SQL + "?" params)
        String placeholders = "?,".repeat(sourceTypes.length);
        placeholders = placeholders.substring(0, placeholders.length() - 1);
        String sql = "SELECT COALESCE(AVG(likes_count + comments_count), 0) FROM social_post "
                + "WHERE request_id = ? AND source_type IN (" + placeholders + ")";
        Object[] params = new Object[sourceTypes.length + 1];
        params[0] = requestId;
        System.arraycopy(sourceTypes, 0, params, 1, sourceTypes.length);
        Double avg = jdbcTemplate.queryForObject(sql, Double.class, params);
        return avg != null ? Math.round(avg) : 0;
    }

    // ── Son analiz tarihi ───────────────────────────────────────────────────

    private java.time.LocalDateTime findLastAnalysisDate(UUID userId) {
        String sql = """
                SELECT process_finished_date FROM report_request
                WHERE user_id = ? AND active = 1 AND process_finished_date IS NOT NULL
                ORDER BY process_finished_date DESC LIMIT 1
                """;
        List<java.time.LocalDateTime> rows = jdbcTemplate.query(sql, (rs, i) -> {
            java.sql.Timestamp ts = rs.getTimestamp("process_finished_date");
            return ts != null ? ts.toLocalDateTime() : null;
        }, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── En son rapor isteği özeti ───────────────────────────────────────────

    private LastReportInfo findLastReport(UUID userId) {
        // 1) En son aktif rapor isteğini al (PENDING dahil tüm statüsler)
        // Önceki implementasyon INNER JOIN kullandığından raporu olmayan (PENDING/PROCESSING)
        // en son istek yerine raporu olan eski bir istek dönebiliyordu — düzeltildi.
        String reqSql = """
                SELECT request_id, status FROM report_request
                WHERE user_id = ? AND active = 1
                ORDER BY created_date DESC LIMIT 1
                """;
        List<LastReportInfo> reqRows = jdbcTemplate.query(reqSql, (rs, i) ->
                new LastReportInfo(
                        rs.getObject("request_id", UUID.class),
                        rs.getString("status"),
                        null), userId);

        if (reqRows.isEmpty()) {
            return null;
        }

        LastReportInfo latest = reqRows.get(0);

        // 2) Bu isteğin raporu var mı? (ayrı sorgu; eski stil — CLAUDE.md Madde 6)
        String reportSql = """
                SELECT report_id FROM report
                WHERE request_id = ?
                ORDER BY created_date DESC LIMIT 1
                """;
        List<UUID> reportRows = jdbcTemplate.query(reportSql,
                (rs, i) -> rs.getObject("report_id", UUID.class),
                latest.getRequestId());

        UUID reportId = reportRows.isEmpty() ? null : reportRows.get(0);
        return new LastReportInfo(latest.getRequestId(), latest.getStatus(), reportId);
    }

    // ── Structured insight ──────────────────────────────────────────────────

    private InsightInfo findLatestInsight(UUID userId) {
        String sql = """
                SELECT r.insight_json
                FROM report r, report_request rr
                WHERE r.request_id = rr.request_id
                  AND rr.user_id = ?
                  AND rr.status IN ('COMPLETED', 'PARTIAL')
                  AND r.insight_json IS NOT NULL
                  AND rr.active = 1
                ORDER BY rr.created_date DESC LIMIT 1
                """;
        List<String> rows = jdbcTemplate.query(sql,
                (rs, i) -> rs.getString("insight_json"), userId);
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return parseInsightJson(rows.get(0));
    }

    private InsightInfo parseInsightJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String topInsight = textOrNull(root, "topInsight");
            String sectorFinding = textOrNull(root, "sectorFinding");
            String recommendation = textOrNull(root, "recommendation");
            List<String> actionPlan = new ArrayList<>();
            JsonNode apNode = root.path("actionPlan");
            if (apNode.isArray()) {
                for (JsonNode item : apNode) {
                    if (!item.isNull() && !item.asText().isBlank()) {
                        actionPlan.add(item.asText());
                    }
                }
            }
            return new InsightInfo(topInsight, sectorFinding, recommendation, actionPlan);
        } catch (Exception ex) {
            log.warn("Insight JSON parse hatası: {}", ex.getMessage());
            return null;
        }
    }

    // ── Hesap kıyaslama tablosu ─────────────────────────────────────────────

    /**
     * Son tamamlanmış analizin hesap bazlı özet metriklerini döndürür.
     * Kaynak tipine göre ayrı sorgular çalıştırılır (eski stil "=" join — CLAUDE.md Madde 6).
     * OWN: 'kendi_hesap', SECTOR: sector_account_name
     */
    private List<AccountComparisonRow> buildAccountComparison(UUID requestId) {
        record Base(String sourceType, String accountName, long avgLikes, long avgComments, long avgViews, long postCount) {}
        List<Base> bases = new ArrayList<>();

        // OWN: kullanıcının kendi hesabı
        // GREATEST(0,...) — Apify bazen -1 döndürebilir; negatif değerleri 0'a kilitle
        String ownSql = """
                SELECT 'OWN' AS source_type, 'kendi_hesap' AS account_name,
                       GREATEST(0, ROUND(AVG(COALESCE(likes_count,    0)))) AS avg_likes,
                       GREATEST(0, ROUND(AVG(COALESCE(comments_count, 0)))) AS avg_comments,
                       GREATEST(0, ROUND(AVG(COALESCE(views_count,    0)))) AS avg_views,
                       COUNT(*) AS post_count
                FROM social_post
                WHERE request_id = ? AND source_type = 'OWN'
                """;
        bases.addAll(jdbcTemplate.query(ownSql, (rs, i) -> new Base(
                rs.getString("source_type"), rs.getString("account_name"),
                rs.getLong("avg_likes"), rs.getLong("avg_comments"),
                rs.getLong("avg_views"), rs.getLong("post_count")), requestId));

        // SECTOR: sektör top-5 hesapları (account adına göre GROUP BY)
        String sectorSql = """
                SELECT 'SECTOR' AS source_type,
                       COALESCE(sector_account_name, 'sektör') AS account_name,
                       GREATEST(0, ROUND(AVG(COALESCE(likes_count,    0)))) AS avg_likes,
                       GREATEST(0, ROUND(AVG(COALESCE(comments_count, 0)))) AS avg_comments,
                       GREATEST(0, ROUND(AVG(COALESCE(views_count,    0)))) AS avg_views,
                       COUNT(*) AS post_count
                FROM social_post
                WHERE request_id = ? AND source_type = 'SECTOR'
                GROUP BY sector_account_name
                """;
        bases.addAll(jdbcTemplate.query(sectorSql, (rs, i) -> new Base(
                rs.getString("source_type"), rs.getString("account_name"),
                rs.getLong("avg_likes"), rs.getLong("avg_comments"),
                rs.getLong("avg_views"), rs.getLong("post_count")), requestId));

        if (bases.isEmpty()) return List.of();

        // Reel sayısı: analysis_json içindeki metrics.contentType.isReel
        // post_analysis ile eski stil "=" join; hesap adı yukarıdaki sorgularla aynı mantıkla türetilir
        String reelOwnSectorSql = """
                SELECT sp.source_type,
                       CASE WHEN sp.source_type = 'OWN' THEN 'kendi_hesap'
                            ELSE COALESCE(sp.sector_account_name, 'sektör')
                       END AS acct_name,
                       pa.analysis_json
                FROM social_post sp, post_analysis pa
                WHERE sp.social_post_id = pa.social_post_id
                  AND sp.request_id = ?
                  AND sp.source_type IN ('OWN', 'SECTOR')
                """;

        java.util.Map<String, Long> reelMap = new java.util.HashMap<>();
        // OWN + SECTOR reels
        jdbcTemplate.query(reelOwnSectorSql, (rs, i) -> {
            String key = rs.getString("source_type") + ":" + rs.getString("acct_name");
            boolean isReel = false;
            String json = rs.getString("analysis_json");
            if (json != null) {
                try {
                    JsonNode node = MAPPER.readTree(json).path("metrics").path("contentType").path("isReel");
                    isReel = node.isBoolean() && node.asBoolean();
                } catch (Exception ignored) {}
            }
            if (isReel) reelMap.merge(key, 1L, Long::sum);
            return null;
        }, requestId);

        List<AccountComparisonRow> result = new ArrayList<>();
        for (Base b : bases) {
            String key = b.sourceType() + ":" + b.accountName();
            long reelCount = reelMap.getOrDefault(key, 0L);
            result.add(new AccountComparisonRow(b.sourceType(), b.accountName(),
                    b.avgLikes(), b.avgComments(), b.avgViews(), reelCount, b.postCount()));
        }
        return result;
    }

    // ── Uyarılar ───────────────────────────────────────────────────────────

    private List<AlertInfo> buildAlerts(UUID userId, UUID lastCompletedRequestId) {
        List<AlertInfo> alerts = new ArrayList<>();

        // 1) Etkileşim düşüşü: son iki tamamlanmış isteğin kendi hesabı etkileşimini karşılaştır
        checkEngagementDropAlert(userId, alerts);

        // 2) Yükselen format: son rapordaki baskın medya tipi
        if (lastCompletedRequestId != null) {
            checkRisingFormatAlert(lastCompletedRequestId, alerts);
        }

        return alerts;
    }

    /** Kendi hesabın son iki raporundaki ort. etkileşimi karşılaştırır. */
    private void checkEngagementDropAlert(UUID userId, List<AlertInfo> alerts) {
        String sql = """
                SELECT request_id FROM report_request
                WHERE user_id = ? AND status IN ('COMPLETED', 'PARTIAL') AND active = 1
                ORDER BY created_date DESC LIMIT 2
                """;
        List<UUID> requestIds = jdbcTemplate.query(sql,
                (rs, i) -> rs.getObject("request_id", UUID.class), userId);
        if (requestIds.size() < 2) {
            return;
        }
        long latestAvg = queryAvgEngagement(requestIds.get(0), new String[]{"OWN"});
        long prevAvg = queryAvgEngagement(requestIds.get(1), new String[]{"OWN"});
        if (prevAvg > 0 && latestAvg < prevAvg) {
            long dropPct = Math.round((double)(prevAvg - latestAvg) / prevAvg * 100);
            if (dropPct >= 10) {
                alerts.add(new AlertInfo("danger",
                        "Etkileşim oranınız bir önceki döneme göre %" + dropPct + " düştü."));
            }
        }
    }

    /** Son rapordaki en yüksek kullanılan medya tipini belirler. */
    private void checkRisingFormatAlert(UUID requestId, List<AlertInfo> alerts) {
        String sql = """
                SELECT media_type, COUNT(*) AS cnt
                FROM social_post
                WHERE request_id = ? AND source_type = 'SECTOR'
                  AND media_type IS NOT NULL
                GROUP BY media_type
                ORDER BY cnt DESC LIMIT 1
                """;
        List<String> rows = jdbcTemplate.query(sql,
                (rs, i) -> rs.getString("media_type"), requestId);
        if (!rows.isEmpty() && rows.get(0) != null) {
            String dominant = rows.get(0);
            alerts.add(new AlertInfo("info",
                    "Sektörde yükselen format: " + formatLabel(dominant) + ". Bu formata yatırım yapmayı düşünün."));
        }
    }

    private String formatLabel(String mediaType) {
        if (mediaType == null) return "";
        return switch (mediaType.toUpperCase(Locale.ROOT)) {
            case "VIDEO" -> "Video / Reel";
            case "IMAGE" -> "Fotoğraf";
            case "CAROUSEL" -> "Çoklu Gönderi (Carousel)";
            default -> mediaType;
        };
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isTextual() && !v.asText().isBlank()) ? v.asText() : null;
    }
}
