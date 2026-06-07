package com.api.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.api.service.ScrapePipelineService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Job kuyruğu tüketicisi / worker (FAZ 5 — CLAUDE.md Bölüm 9).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Component.
 *
 * Scheduler (FAZ 4) tarafından kuyruğa basılan JobMessage'ları dinler ve
 * scraping pipeline'ını (Apify + social_post) çalıştırır.
 *
 * Mesaj gövdesi JSON'dur; Boot, RabbitConfig'teki MessageConverter (Jackson2Json) bean'ini
 * listener container factory'sine otomatik uygular; ayrıca dönüştürücü yapılandırması gerekmez.
 *
 * Hata yönetimi: pipeline patlarsa hata loglanır ve mesaj ack edilir (return). Böylece
 * "poison message" sonsuz yeniden teslim döngüsüne girmez.
 * TODO(uyum): İleride retry + DLQ (dead-letter) politikası eklenebilir.
 *
 * Kuyruk adı app.messaging.job-queue'dan gelir. app.worker.enabled=false ise (local profil)
 * bu bean oluşturulmaz; böylece local'de broker zorunlu olmaz (scheduler ile aynı yaklaşım).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class JobWorker {

	// Scraping pipeline'ı (Apify + social_post)
	private final ScrapePipelineService scrapePipelineService;

	/**
	 * Kuyruktan gelen job mesajını işler.
	 * @RabbitListener queue adını property placeholder ile okur.
	 *
	 * @param message JSON'dan deserialize edilen job mesajı (userJobId taşır)
	 */
	@RabbitListener(queues = "${app.messaging.job-queue:socialagent.job.queue}")
	public void onJobMessage(JobMessage message) {
		// Mesaj veya id boşsa atla
		if (message == null || message.userJobId() == null) {
			log.warn("Geçersiz job mesajı alındı (null), atlanıyor.");
			return;
		}
		log.info("Job mesajı alındı, işleniyor: userJobId={}", message.userJobId());
		try {
			// Pipeline'ı çalıştır (scraping + social_post yazımı)
			scrapePipelineService.processJob(message.userJobId());
		} catch (Exception ex) {
			// Hata -> logla ve ack et (sonsuz redelivery'i önle)
			log.error("Job işlenirken hata: userJobId={}, hata={}", message.userJobId(), ex.getMessage(), ex);
		}
	}
}
