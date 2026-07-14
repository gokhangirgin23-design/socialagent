package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.DashboardSummaryDto;

/**
 * Sorun 1 (kompozit hesap skoru) regresyon testi — gerçek JdbcTemplate + H2 üzerinden. Eski ham
 * oran formülü küçük hesaplarda skoru hep 1'e çakıyordu; bu test yeni SQL'in (COALESCE + ORDER BY
 * post_date + COUNT) H2'de sorunsuz çalıştığını ve skorun artık 1 bandında SIKIŞMADIĞINI doğrular.
 *
 * @Transactional: her test sonunda ROLLBACK edilir, H2'ye kalıcı veri yazılmaz.
 */
@SpringBootTest
@Transactional
@DirtiesContext
class DashboardServiceScoreIT {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID insertCompletedRequest(UUID userId) {
        UUID requestId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO report_request
                    (request_id, user_id, report_type, queue_pushed, status, attempt_count, active,
                     created_date, updated_date, is_free_usage)
                VALUES (?, ?, 'NONE', 1, 'COMPLETED', 0, 1, ?, ?, 0)
                """, requestId, userId, now, now);
        return requestId;
    }

    private void insertPost(UUID requestId, String sourceType, long likes, long comments, LocalDateTime postDate) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO social_post
                    (social_post_id, request_id, source_type, platform, platform_post_id,
                     likes_count, comments_count, post_date, created_date, updated_date)
                VALUES (?, ?, ?, 'INSTAGRAM', ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), requestId, sourceType, UUID.randomUUID().toString(),
                likes, comments, postDate, now, now);
    }

    @Test
    void kucukHesapEskiFormuldeSkoru1eCakiyorduYeniFormuldeCakmiyor() {
        UUID userId = UUID.randomUUID();
        UUID requestId = insertCompletedRequest(userId);
        LocalDateTime base = LocalDateTime.now().minusDays(10);

        // Küçük hesap: kendi 3 post, düşük ama sektöre yakın etkileşim; sektör devleri 100x büyük.
        insertPost(requestId, "OWN", 10, 2, base);
        insertPost(requestId, "OWN", 12, 3, base.plusDays(3));
        insertPost(requestId, "OWN", 20, 5, base.plusDays(6));
        insertPost(requestId, "SECTOR", 1000, 200, base);
        insertPost(requestId, "SECTOR", 1100, 250, base.plusDays(3));

        DashboardSummaryDto summary = dashboardService.buildSummary(userId);

        assertNotNull(summary.getAccountScore(), "Kendi postu olan hesapta skor null olmamalı");
        assertNotNull(summary.getScoreBreakdown());
        // Eski formülde ratio ~0.013 -> score = round(min(0.013/2,1)*100) = 1. Yeni kompozit
        // formülde log2 sıkıştırma + tempo + trend puanları sayesinde 1'e çakılmamalı.
        assertEquals(true, summary.getAccountScore() > 1,
                "Küçük hesap skoru artık log2 ölçek + tempo/trend puanlarıyla 1'e çakılmamalı, aldı: "
                        + summary.getAccountScore());
        assertEquals(3L, summary.getScoreBreakdown().getOwnPostCount());
    }

    @Test
    void kendiPostuOlmayanHesapicinSkorNullDoner() {
        UUID userId = UUID.randomUUID();
        UUID requestId = insertCompletedRequest(userId);
        insertPost(requestId, "SECTOR", 1000, 200, LocalDateTime.now());

        DashboardSummaryDto summary = dashboardService.buildSummary(userId);

        assertNull(summary.getAccountScore(), "Kendi postu hiç yoksa skor null dönmeli (0/1 değil)");
        assertNull(summary.getScoreBreakdown());
    }

    @Test
    void yukselenTrendDusenTrendeGoreDahaYuksekPuanAlir() {
        UUID userIdRising = UUID.randomUUID();
        UUID risingRequest = insertCompletedRequest(userIdRising);
        LocalDateTime base = LocalDateTime.now().minusDays(20);
        // Eski yarı düşük etkileşim, yeni yarı yüksek etkileşim (yükselen trend).
        insertPost(risingRequest, "OWN", 5, 1, base);
        insertPost(risingRequest, "OWN", 6, 1, base.plusDays(2));
        insertPost(risingRequest, "OWN", 50, 10, base.plusDays(15));
        insertPost(risingRequest, "OWN", 60, 12, base.plusDays(18));

        UUID userIdFalling = UUID.randomUUID();
        UUID fallingRequest = insertCompletedRequest(userIdFalling);
        // Eski yarı yüksek etkileşim, yeni yarı düşük etkileşim (düşen trend) — aynı ownAvg/othersAvg.
        insertPost(fallingRequest, "OWN", 50, 10, base);
        insertPost(fallingRequest, "OWN", 60, 12, base.plusDays(2));
        insertPost(fallingRequest, "OWN", 5, 1, base.plusDays(15));
        insertPost(fallingRequest, "OWN", 6, 1, base.plusDays(18));

        DashboardSummaryDto rising = dashboardService.buildSummary(userIdRising);
        DashboardSummaryDto falling = dashboardService.buildSummary(userIdFalling);

        assertEquals(true,
                rising.getScoreBreakdown().getTrendPoints() > falling.getScoreBreakdown().getTrendPoints(),
                "Yükselen trend, düşen trendden daha yüksek trendPoints almalı");
    }
}
