package com.api.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.api.service.ScrapePipelineService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rapor isteği kuyruk tüketicisi / worker (CLAUDE.md Bölüm 9 — yeni: scheduler yok, FIFO kuyruk).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Component.
 *
 * ReportRequestService tarafından kuyruğa basılan JobMessage'ları dinler ve
 * scraping pipeline'ını (Apify + social_post + AI + rapor) çalıştırır.
 *
 * Hata yönetimi: pipeline patlarsa hata loglanır ve mesaj ack edilir (return).
 * app.worker.enabled=false ise (local profil) bu bean oluşturulmaz.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class JobWorker {

    // Scraping + analiz + rapor pipeline'ı
    private final ScrapePipelineService scrapePipelineService;

    /**
     * Kuyruktan gelen rapor isteği mesajını işler.
     *
     * @param message JSON'dan deserialize edilen mesaj (requestId taşır)
     */
    @RabbitListener(queues = "${app.messaging.job-queue:socialagent.job.queue}")
    public void onJobMessage(JobMessage message) {
        // Mesaj veya id boşsa atla
        if (message == null || message.requestId() == null) {
            log.warn("Geçersiz rapor isteği mesajı alındı (null), atlanıyor.");
            return;
        }
        log.info("Rapor isteği mesajı alındı, işleniyor: requestId={}", message.requestId());
        try {
            // Pipeline'ı çalıştır (scraping + analiz + rapor)
            scrapePipelineService.processRequest(message.requestId());
        } catch (Exception ex) {
            // Hata -> logla ve ack et (sonsuz redelivery'i önle)
            log.error("Rapor isteği işlenirken hata: requestId={}, hata={}", message.requestId(), ex.getMessage(), ex);
        }
    }
}
