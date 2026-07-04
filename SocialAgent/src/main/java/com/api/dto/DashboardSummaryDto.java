package com.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * POST /dashboard/summary yanıtı — dashboard'ı tek çağrıda besler.
 * Tüm alanlar null olabilir (kullanıcının henüz raporu/hesabı yok ise).
 */
@Getter
@Builder
@AllArgsConstructor
public class DashboardSummaryDto {

    // 0-100 hesap skoru (kendi vs rakip/sektör etkileşim oranı); rapor yoksa null
    private Integer accountScore;

    // İzlenen rakip hesap bilgisi
    private MonitoredInfo monitored;

    // Son tamamlanan analizin bitiş tarihi; henüz yoksa null
    private LocalDateTime lastAnalysisDate;

    // En son rapor isteği özeti; henüz yoksa null
    private LastReportInfo lastReport;

    // En son COMPLETED/PARTIAL raporun structured insight JSON'u; yoksa null
    private InsightInfo latestInsight;

    // Türetilmiş uyarılar (etkileşim düşüşü, rakip aktivitesi vb.)
    private List<AlertInfo> alerts;

    // Kullanıcı cüzdan özeti
    private WalletInfo wallet;

    // Hesap bazlı kıyaslama satırları (son tamamlanmış analizden); yoksa boş liste
    private List<AccountComparisonRow> accountComparison;

    // ── İç veri sınıfları ─────────────────────────────────────────────────

    @Getter
    @AllArgsConstructor
    public static class AccountComparisonRow {
        private String sourceType;   // OWN | SECTOR | MONITORED
        private String accountName;
        private long avgLikes;
        private long avgComments;
        private long avgViews;
        private long reelCount;
        private long postCount;
    }

    @Getter
    @AllArgsConstructor
    public static class MonitoredInfo {
        private int count;
        private int max; // sabit 5
    }

    @Getter
    @AllArgsConstructor
    public static class LastReportInfo {
        private UUID requestId;
        private String status;
        private UUID reportId;
    }

    @Getter
    @AllArgsConstructor
    public static class InsightInfo {
        private String topInsight;
        private String competitorFinding;
        private String recommendation;
        private List<String> actionPlan;
    }

    @Getter
    @AllArgsConstructor
    public static class AlertInfo {
        private String level; // danger | warning | info
        private String text;
    }

    @Getter
    @AllArgsConstructor
    public static class WalletInfo {
        private BigDecimal balance;
        private String currency;
        private long creditBalance;
    }
}
