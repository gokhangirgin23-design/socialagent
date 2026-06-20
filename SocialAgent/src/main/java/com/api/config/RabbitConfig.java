package com.api.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;

/**
 * RabbitMQ altyapı yapılandırması (FAZ 4 — CLAUDE.md Bölüm 9).
 * Scheduler tarafından üretilen "job" mesajları için queue + exchange + binding tanımlar.
 * vhost değeri profil bazlı yml'den gelir (test -> /test, prod -> /prod).
 * Kuyruk/exchange/routing-key adları app.messaging altından (AppProperties) okunur.
 * Mesaj gövdesi JSON (Jackson2JsonMessageConverter) olarak taşınır; FAZ 5 worker aynı converter ile okur.
 */
@Configuration
@RequiredArgsConstructor
public class RabbitConfig {

	// app.messaging.* ayarları (kuyruk/exchange/routing-key adları)
	private final AppProperties appProperties;

	/**
	 * Job kuyruğu (durable: broker yeniden başlasa da kuyruk kaybolmaz).
	 * RabbitAdmin bu bean'i context açılışında broker'da otomatik declare eder.
	 */
	@Bean
	Queue jobQueue() {
		// durable=true -> kalıcı kuyruk
		return new Queue(appProperties.getMessaging().getJobQueue(), true);
	}

	/**
	 * Direct exchange — routing-key birebir eşleşmesiyle yönlendirir.
	 */
	@Bean
	DirectExchange jobExchange() {
		// durable=true, autoDelete=false
		return new DirectExchange(appProperties.getMessaging().getJobExchange(), true, false);
	}

	/**
	 * Exchange -> Queue bağlama (routing-key ile).
	 */
	@Bean
	Binding jobBinding(Queue jobQueue, DirectExchange jobExchange) {
		// Exchange'e gelen, routing-key'i eşleşen mesajları job kuyruğuna ilet
		return BindingBuilder.bind(jobQueue).to(jobExchange).with(appProperties.getMessaging().getJobRoutingKey());
	}

	/**
	 * Mesaj gövdesini JSON olarak (de)serialize eden converter.
	 * Hem producer (RabbitTemplate) hem consumer (FAZ 5 listener) bunu kullanır.
	 */
	@Bean
	MessageConverter jsonMessageConverter() {
		return new Jackson2JsonMessageConverter();
	}

	/**
	 * JSON converter ile yapılandırılmış RabbitTemplate.
	 * ConnectionFactory Spring Boot tarafından spring.rabbitmq.* ayarlarından auto-configure edilir.
	 */
	@Bean
	RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
		RabbitTemplate template = new RabbitTemplate(connectionFactory);
		// Giden mesajları JSON'a çevir
		template.setMessageConverter(jsonMessageConverter);
		return template;
	}

	/**
	 * @RabbitListener container factory'sine JSON converter açıkça bağlanır.
	 * Spring Boot 4.x'te MessageConverter otomatik enjekte edilmiyor;
	 * açık bean tanımı olmadan SimpleMessageConverter kullanılır ve
	 * JobMessage deserialize edilemeyip mesajlar drop edilir.
	 */
	@Bean
	SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
			ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		// Gelen JSON mesajlarını JobMessage record'una çevir
		factory.setMessageConverter(jsonMessageConverter);
		return factory;
	}
}
