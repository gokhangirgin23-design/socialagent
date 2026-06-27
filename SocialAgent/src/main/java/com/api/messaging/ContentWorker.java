package com.api.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.api.service.ContentPipelineService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * İçerik üretim kuyruğu tüketicisi.
 * ContentMessage'ları dinler ve ContentPipelineService üzerinden üretimi başlatır.
 * app.worker.enabled=false ise (local profil) bu bean oluşturulmaz.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ContentWorker {

    private final ContentPipelineService contentPipelineService;

    @RabbitListener(queues = "${app.content.queue:spectiqs.content.queue}")
    public void onContentMessage(ContentMessage message) {
        if (message == null || message.contentRequestId() == null) {
            log.warn("Geçersiz içerik isteği mesajı alındı (null), atlanıyor.");
            return;
        }
        log.info("İçerik üretim isteği alındı: contentRequestId={}", message.contentRequestId());
        try {
            contentPipelineService.process(message.contentRequestId());
        } catch (Exception ex) {
            log.error("İçerik üretimi sırasında beklenmeyen hata: contentRequestId={}, hata={}",
                    message.contentRequestId(), ex.getMessage(), ex);
        }
    }
}
