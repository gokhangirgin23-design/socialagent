package com.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.DashboardSummaryDto;
import com.api.dto.DashboardSummaryDto.AlertInfo;
import com.api.dto.DashboardSummaryDto.InsightInfo;
import com.api.dto.DashboardSummaryDto.LastReportInfo;
import com.api.dto.DashboardSummaryDto.MonitoredInfo;
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

        // 2) İzlenen hesap sayısı
        MonitoredInfo monitored = buildMonitoredInfo(userId);

        // 3) Son tamamlanmış isteğin id'si (skor + insight için)
        UUID lastCompletedRequestId = findLastCompletedRequestId(userId);

        // 4) Hesap skoru (kendi postlar vs diğerleri)
        Integer accountScore = (lastCompletedRequestId != null)
                ? computeAccountScore(lastCompletedRequestId) : null;

        // 5) Son analiz tarihi (process_finished_date)
        java.time.LocalDateTime lastAnalysisDate = findLastAnalysisDate(userId);

        // 6) En son rapor isteği özeti
        LastReportInfo lastReport = findLastReport(userId);

        // 7) Structured insight JSON (en son COMPLETED/PARTIAL rapordan)
        InsightInfo latestInsight = findLatestInsight(userId);

        // 8) Uyarılar (etkileşim düşüşü, rakip aktivitesi, format trendi)
        List<AlertInfo> alerts = buildAlerts(userId, lastCompletedRequestId);

        return DashboardSummaryDto.builder()
                .accountScore(accountScore)
                .monitored(monitored)
                .lastAnalysisDate(lastAnalysisDate)
                .lastReport(lastReport)
                .latestInsight(latestInsight)
                .alerts(alerts)
                .wallet(wallet)
                .build();
    }

    // ── Cüzdan ──────────────────────────────────────────────────────────────

    private WalletInfo buildWalletInfo(UUID userId) {
        UserPayment w = paymentService.findWallet(userId);
        if (w == null) {
            return new WalletInfo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), "TL");
        }
        return new WalletInfo(
                w.getBalance().setScale(2, RoundingMode.HALF_UP),
                w.getCurrency() != null ? w.getCurrency() : "TL");
    }

    // ── İzlenen hesap sayısı ────────────────────────────────────────────────

    private MonitoredInfo buildMonitoredInfo(UUID userId) {
        String sql = "SELECT COUNT(*) FROM user_monitored_account WHERE user_id = ? AND active = 1";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId);
        return new MonitoredInfo(count != null ? count : 0, 5);
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

    /**
     * 0-100 hesap skoru: kendi postların ort. etkileşimi / diğerlerinin ort. etkileşimi.
     * Basit MVP formülü; ileride kalibre edilebilir.
     */
    private int computeAccountScore(UUID requestId) {
        long ownAvg = queryAvgEngagement(requestId, new String[]{"OWN"});
        long othersAvg = queryAvgEngagement(requestId, new String[]{"SECTOR", "MONITORED"});

        if (ownAvg == 0) {
            return 0;
        }
        if (othersAvg == 0) {
            // Karşılaştırma için başka hesap yok; orta skor
            return 50;
        }
        // ratio: 1.0 = eşit (50 puan), 2.0 = 2x daha iyi (100 puan)
        double ratio = (double) ownAvg / othersAvg;
        int score = (int) Math.round(Math.min(ratio / 2.0, 1.0) * 100);
        return Math.max(0, Math.min(100, score));
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
                + "WHERE request_id = ? AND source_type IN (" + placeholders + ") AND active = 1";
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
            String competitorFinding = textOrNull(root, "competitorFinding");
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
            return new InsightInfo(topInsight, competitorFinding, recommendation, actionPlan);
        } catch (Exception ex) {
            log.warn("Insight JSON parse hatası: {}", ex.getMessage());
            return null;
        }
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
                WHERE request_id = ? AND source_type IN ('SECTOR', 'MONITORED') AND active = 1
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
        return switch (mediaType.toUpperCase()) {
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
