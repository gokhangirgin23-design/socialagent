package com.api.local;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.api.service.ScrapePipelineService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * LOCAL profil istek çalıştırıcısı — JobWorker'ın RabbitMQ'suz karşılığı (sadece iç test).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service. Sadece local profilde oluşur.
 *
 * report_request tablosundan FIFO sırayla bekleyen istekleri seçer ve gerçek pipeline'ı çağırır.
 * "Bekleyen" = active=1 ve henüz COMPLETED/FAILED raporu olmayan istek.
 * Apify/OpenAI/Gemini çağrıları local'de @Primary dummy bean'lerle taklit edilir.
 */
@Slf4j
@Service
@Profile("local")
@RequiredArgsConstructor
public class LocalJobRunner {

    // FIFO istek seçimi için JdbcTemplate native (CLAUDE.md Madde 6)
    private final JdbcTemplate jdbcTemplate;

    // Worker ile aynı gerçek pipeline (local'de dış çağrılar dummy)
    private final ScrapePipelineService scrapePipelineService;

    // Tek tetiklemede en fazla işlenecek istek (run-all güvenlik tavanı)
    private static final int MAX_BATCH = 50;

    /**
     * Sıradaki (en eski) bekleyen rapor isteğini işler.
     *
     * @return işlenen isteğin id'si; bekleyen istek yoksa null
     */
    public UUID runNextRequest() {
        UUID requestId = findNextPendingId(Set.of());
        if (requestId == null) {
            log.info("[LOCAL] İşlenecek bekleyen rapor isteği yok.");
            return null;
        }
        log.info("[LOCAL] Rapor isteği işleniyor (FIFO): requestId={}", requestId);
        scrapePipelineService.processRequest(requestId);
        return requestId;
    }

    /**
     * Belirli bir rapor isteğini id ile işler (bekleme sırasına bakmaksızın).
     *
     * @param requestId işlenecek istek
     */
    public void runRequest(UUID requestId) {
        log.info("[LOCAL] Rapor isteği işleniyor (id ile): requestId={}", requestId);
        scrapePipelineService.processRequest(requestId);
    }

    /**
     * Bekleyen tüm istekleri FIFO sırayla, hiç bekleyen kalmayana kadar işler (tavan: MAX_BATCH).
     *
     * @return işlenen istek id listesi (FIFO sırasıyla)
     */
    public List<UUID> runAllPending() {
        List<UUID> processed = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < MAX_BATCH; i++) {
            UUID requestId = findNextPendingId(seen);
            if (requestId == null) {
                break;
            }
            seen.add(requestId);
            log.info("[LOCAL] Toplu işleme {}/{}: requestId={}", i + 1, MAX_BATCH, requestId);
            scrapePipelineService.processRequest(requestId);
            processed.add(requestId);
        }
        log.info("[LOCAL] Toplu işleme tamamlandı: işlenen={}", processed.size());
        return processed;
    }

    /**
     * Bekleyen rapor isteklerini (COMPLETED/FAILED raporu olmayan) FIFO sırasıyla listeler.
     */
    public List<PendingRequest> listPending() {
        return loadEligibleRequests().stream()
                .map(r -> new PendingRequest(r.requestId(), r.createdDate()))
                .toList();
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    /** 'exclude' içindekiler hariç en eski uygun istek id'sini döndürür; yoksa null. */
    private UUID findNextPendingId(Set<UUID> exclude) {
        for (EligibleRequest r : loadEligibleRequests()) {
            if (!exclude.contains(r.requestId())) {
                return r.requestId();
            }
        }
        return null;
    }

    /**
     * active=1 ve henüz COMPLETED/FAILED raporu olmayan istekleri FIFO ile döndürür.
     * NOT EXISTS ile rapor tamamlanmamışlık koşulu uygulanır.
     */
    private List<EligibleRequest> loadEligibleRequests() {
        String sql = """
                SELECT rr.request_id, rr.created_date
                FROM report_request rr
                WHERE rr.active = 1
                  AND NOT EXISTS (
                        SELECT 1 FROM report r
                        WHERE r.request_id = rr.request_id
                          AND r.status IN ('COMPLETED', 'FAILED')
                  )
                ORDER BY rr.created_date ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new EligibleRequest(
                rs.getObject("request_id", UUID.class),
                rs.getTimestamp("created_date") != null
                        ? rs.getTimestamp("created_date").toLocalDateTime() : null));
    }

    /** Uygun istek satırı (iç taşıyıcı). */
    private record EligibleRequest(UUID requestId, LocalDateTime createdDate) {
    }

    /**
     * Bekleyen istek özeti (id + oluşturulma tarihi).
     */
    public record PendingRequest(UUID requestId, LocalDateTime createdDate) {
    }
}
