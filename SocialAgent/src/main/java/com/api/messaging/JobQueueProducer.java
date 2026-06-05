package com.api.messaging;

import java.util.UUID;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.api.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Job kuyruğu producer'ı (FAZ 4 — CLAUDE.md Bölüm 9).
 * Scheduler tarafından seçilen job'ları RabbitMQ exchange'ine basar.
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

	/**
	 * Verilen job id'sini kuyruğa basar.
	 * Broker'a bağlanılamazsa exception fırlatır; çağıran (Scheduler) claim'i geri alır.
	 *
	 * @param userJobId kuyruğa basılacak job'ın id'si
	 */
	public void publishJob(UUID userJobId) {
		// Tip güvenli mesaj gövdesi oluştur
		JobMessage message = new JobMessage(userJobId);
		// Exchange + routing-key adlarını config'ten al
		String exchange = appProperties.getMessaging().getJobExchange();
		String routingKey = appProperties.getMessaging().getJobRoutingKey();
		// Mesajı exchange'e gönder (RabbitTemplate JSON'a çevirir)
		rabbitTemplate.convertAndSend(exchange, routingKey, message);
		// Bilgi log'u (worker FAZ 5'te tüketecek)
		log.info("Job kuyruğa basıldı: userJobId={}, exchange={}, routingKey={}", userJobId, exchange, routingKey);
	}
}
