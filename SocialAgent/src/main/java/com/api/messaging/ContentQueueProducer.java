package com.api.messaging;

import java.util.UUID;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.api.config.AppProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * İçerik üretim kuyruğu producer'ı.
 * ContentRequestService tarafından içerik isteği oluşturulunca / yeniden kuyruğa basılınca çağrılır.
 * LOCAL profilde push atlanır (broker zorunlu değil).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentQueueProducer {

    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;
    private final Environment environment;

    private boolean skipPublish;

    @PostConstruct
    void init() {
        this.skipPublish = environment.matchesProfiles("local");
        if (skipPublish) {
            log.info("LOCAL profil: content RabbitMQ push devre dışı.");
        }
    }

    /**
     * Verilen content isteği id'sini içerik üretim kuyruğuna basar.
     */
    public void publish(UUID contentRequestId) {
        if (skipPublish) {
            log.info("[LOCAL] Content queue push atlandı: contentRequestId={}", contentRequestId);
            return;
        }
        ContentMessage message = new ContentMessage(contentRequestId);
        String exchange = appProperties.getContent().getExchange();
        String routingKey = appProperties.getContent().getRoutingKey();
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
        log.info("İçerik isteği kuyruğa basıldı: contentRequestId={}", contentRequestId);
    }
}
