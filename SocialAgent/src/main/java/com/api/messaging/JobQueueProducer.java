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

	// Aktif profilleri okumak için (local'de RabbitMQ push'u atlanır)
	private final Environment environment;

	// LOCAL profilde true: gerçek push yapılmaz, çağrı sessizce "başarılı" sayılır.
	// Diğer ortamlarda (prod/test) false kalır ve mevcut push mantığı aynen çalışır.
	private boolean skipPublish;

	/**
	 * Bean kurulduktan sonra aktif profili kontrol eder.
	 * Sadece "local" profilde push atlanır; broker zorunlu olmaz.
	 */
	@PostConstruct
	void init() {
		this.skipPublish = environment.matchesProfiles("local");
		if (skipPublish) {
			log.info("LOCAL profil: RabbitMQ push devre dışı; publishJob çağrıları taklit edilecek.");
		}
	}

	/**
	 * Verilen job id'sini kuyruğa basar.
	 * Broker'a bağlanılamazsa exception fırlatır; çağıran (Scheduler) claim'i geri alır.
	 *
	 * @param userJobId kuyruğa basılacak job'ın id'si
	 */
	public void publishJob(UUID userJobId) {
		// LOCAL profil: gerçek push yapma; çağrı başarılı kabul edilir (broker gerekmez).
		if (skipPublish) {
			log.info("[LOCAL] RabbitMQ push atlandı (taklit başarılı): userJobId={}", userJobId);
			return;
		}
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
