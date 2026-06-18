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
 * Rapor isteği kuyruk producer'ı (CLAUDE.md Bölüm 9 — yeni: direkt push, scheduler yok).
 * ReportRequestService tarafından istek oluşturulunca hemen çağrılır.
 * Mesaj gövdesi JobMessage (JSON); vhost profil bazlıdır.
 * Service interface yok (CLAUDE.md Madde 1); concrete @Component.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobQueueProducer {

    // JSON converter ile yapılandırılmış template (RabbitConfig'te tanımlı)
    private final RabbitTemplate rabbitTemplate;

    // Exchange / routing-key adlarını okumak için
    private final AppProperties appProperties;

    // Aktif profilleri okumak için (local'de RabbitMQ push'u atlanır)
    private final Environment environment;

    // LOCAL profilde true: gerçek push yapılmaz, çağrı sessizce "başarılı" sayılır.
    private boolean skipPublish;

    /**
     * Bean kurulduktan sonra aktif profili kontrol eder.
     * Sadece "local" profilde push atlanır; broker zorunlu olmaz.
     */
    @PostConstruct
    void init() {
        this.skipPublish = environment.matchesProfiles("local");
        if (skipPublish) {
            log.info("LOCAL profil: RabbitMQ push devre dışı; publishRequest çağrıları taklit edilecek.");
        }
    }

    /**
     * Verilen rapor isteği id'sini kuyruğa basar.
     * Broker'a bağlanılamazsa exception fırlatır; çağıran (ReportRequestService) hatayı
     * queue_error alanına yazar.
     *
     * @param requestId kuyruğa basılacak rapor isteğinin id'si
     */
    public void publishRequest(UUID requestId) {
        // LOCAL profil: gerçek push yapma; çağrı başarılı kabul edilir (broker gerekmez).
        if (skipPublish) {
            log.info("[LOCAL] RabbitMQ push atlandı (taklit başarılı): requestId={}", requestId);
            return;
        }
        // Tip güvenli mesaj gövdesi oluştur
        JobMessage message = new JobMessage(requestId);
        // Exchange + routing-key adlarını config'ten al
        String exchange = appProperties.getMessaging().getJobExchange();
        String routingKey = appProperties.getMessaging().getJobRoutingKey();
        // Mesajı exchange'e gönder (RabbitTemplate JSON'a çevirir)
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
        log.info("Rapor isteği kuyruğa basıldı: requestId={}, exchange={}, routingKey={}",
                requestId, exchange, routingKey);
    }
}
