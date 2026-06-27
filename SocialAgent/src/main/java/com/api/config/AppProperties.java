package com.api.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Uygulama bazlı yapılandırma (application.yml -> "app" kökü). JWT ve Google SSO ayarları buradan okunur (FAZ 1).
 */
@Configuration
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

	// JWT ile ilgili ayarlar
	private Jwt jwt = new Jwt();
	// Google SSO ile ilgili ayarlar
	private Google google = new Google();
	// RabbitMQ kuyruk/exchange ayarları (FAZ 4)
	private Messaging messaging = new Messaging();
	// Apify scraping ayarları (FAZ 5)
	private Apify apify = new Apify();
	// Worker (RabbitMQ consumer) ayarları (FAZ 5)
	private Worker worker = new Worker();
	// AI analiz (LangChain4j) ayarları (FAZ 6)
	private Ai ai = new Ai();
	// Bildirim (mail + push) ayarları (FAZ 8)
	private Notification notification = new Notification();
	// Admin işlemleri için gizli anahtar (X-Admin-Key header)
	private Admin admin = new Admin();
	// CORS (frontend origin) ayarları — React/localhost ve spectiqs domainleri
	private Cors cors = new Cors();

	/**
	 * CORS yapılandırması. İzinli origin'ler application.yml -> app.cors.allowed-origins
	 * (veya CORS_ALLOWED_ORIGINS env) üzerinden gelir. Pattern desteklenir (ör. http://localhost:*).
	 */
	@Getter
	@Setter
	public static class Cors {
		// İzin verilen origin listesi/pattern'leri (boşsa CORS pratikte kapalı kalır)
		private List<String> allowedOrigins = new ArrayList<>();
	}

	/**
	 * JWT ayarları.
	 */
	@Getter
	@Setter
	public static class Jwt {
		// İmzalama anahtarı (HS256 için en az 32 karakter olmalı)
		private String secret;
		// Access token ömrü (dakika)
		private long accessTokenExpirationMinutes = 30;
		// Refresh token ömrü (gün)
		private long refreshTokenExpirationDays = 30;
	}

	/**
	 * Google SSO ayarları.
	 */
	@Getter
	@Setter
	public static class Google {
		// Beklenen audience (OAuth client id)
		private String clientId;
		// Gerçek imza doğrulaması yapılsın mı? (local'de false ile dev kolaylığı)
		private boolean verificationEnabled = true;
	}

	/**
	 * RabbitMQ kuyruk yapılandırması (FAZ 4). Adlar profil bazlı override edilebilir; vhost spring.rabbitmq.virtual-host ile gelir.
	 */
	@Getter
	@Setter
	public static class Messaging {
		// Job kuyruğunun adı
		private String jobQueue = "socialagent.job.queue";
		// Job exchange adı (direct)
		private String jobExchange = "socialagent.job.exchange";
		// Exchange -> queue routing-key
		private String jobRoutingKey = "socialagent.job";
	}

	/**
	 * Apify scraping yapılandırması (FAZ 5 — CLAUDE.md Bölüm 10, D1). Aktör id'leri API yolunda "~" ile yazılır (ör. apify~instagram-search-scraper).
	 */
	@Getter
	@Setter
	public static class Apify {
		// Apify API token (prod/test'te env ile; boşsa scraping atlanır)
		private String token;
		// Apify API kök adresi
		private String baseUrl = "https://api.apify.com/v2";
		// Sektör top-5 keyword/profil arama aktörü (D1)
		private String profileActorId = "apify~instagram-search-scraper";
		// Hesap gönderilerini çeken aktör
		private String postActorId = "apify~instagram-post-scraper";
		// Sektör "top N" profil sayısı (D1 -> 5)
		private int topProfilesLimit = 5;
		// Hesap başına çekilecek son gönderi sayısı (-> 5)
		private int recentPostsLimit = 5;
		// run-sync uzun sürebileceğinden okuma zaman aşımı (sn)
		private long timeoutSeconds = 120;
	}

	/**
	 * Worker (RabbitMQ consumer) yapılandırması (FAZ 5).
	 */
	@Getter
	@Setter
	public static class Worker {
		// Worker aktif mi? (local'de broker zorunlu olmasın diye kapatılabilir)
		private boolean enabled = true;
	}

	/**
	 * AI analiz yapılandırması (FAZ 6 — CLAUDE.md Bölüm 11, D3). caption/TEXT -> OpenAI; IMAGE/VIDEO/CAROUSEL -> Gemini Vision. API key'leri boşsa (local/dev) ilgili model devre dışı kalır; analiz
	 * atlanır, uygulama patlamaz (Apify token yaklaşımıyla aynı felsefe).
	 */
	@Getter
	@Setter
	public static class Ai {
		// AI analizi tamamen açık mı? (local'de key zorunlu olmasın diye kapatılabilir)
		private boolean enabled = true;
		// OpenAI ayarları (TEXT/caption analizi)
		private OpenAi openai = new OpenAi();
		// Gemini ayarları (görsel/video/carousel analizi)
		private Gemini gemini = new Gemini();

		/**
		 * OpenAI (caption/TEXT) ayarları.
		 */
		@Getter
		@Setter
		public static class OpenAi {
			// OpenAI API anahtarı (prod/test env ile; boşsa TEXT analizi atlanır)
			private String apiKey;
			// Kullanılacak metin modeli
			private String model = "gpt-4o-mini";
			// Üretim sıcaklığı (analizde düşük tutulur -> tutarlı JSON)
			private double temperature = 0.2;
			// İstek zaman aşımı (sn)
			private long timeoutSeconds = 60;
		}

		/**
		 * Google Gemini (IMAGE/VIDEO/CAROUSEL) ayarları.
		 */
		@Getter
		@Setter
		public static class Gemini {
			// Gemini API anahtarı (prod/test env ile; boşsa medya analizi atlanır)
			private String apiKey;
			// Kullanılacak vision modeli
			private String model = "gemini-2.0-flash";
			// Üretim sıcaklığı
			private double temperature = 0.2;
			// İstek zaman aşımı (sn)
			private long timeoutSeconds = 60;
		}
	}

	/**
	 * Bildirim yapılandırması (FAZ 8 — CLAUDE.md Bölüm 12). Rapor tamamlanınca notification kaydı her zaman yazılır; mail/push KANALLARI bu bayraklarla açılır/kapanır. Yapılandırma yoksa ilgili kanal
	 * sessizce atlanır (Apify token / AI key felsefesiyle aynı — uygulama çökmez).
	 *
	 * SMTP ayarları standart Spring "spring.mail.*" altında verilir (env: SPRING_MAIL_HOST vb.); spring.mail.host verilmezse JavaMailSender bean'i oluşmaz ve mail otomatik atlanır.
	 */
	@Getter
	@Setter
	public static class Notification {
		// E-posta kanalı açık mı? (local'de false; prod'da SMTP verilince true)
		private boolean mailEnabled = false;
		// Push kanalı açık mı? (FCM stub; varsayılan kapalı)
		private boolean pushEnabled = false;
		// Giden e-posta "from" adresi (boşsa SMTP varsayılanı kullanılır)
		private String fromAddress;
	}

	/**
	 * Admin endpoint güvenlik anahtarı. X-Admin-Key header'ı bu değerle eşleşmezse UNAUTHORIZED döner.
	 */
	@Getter
	@Setter
	public static class Admin {
		// Admin endpoint'leri için gizli anahtar (env: ADMIN_KEY; boşsa admin endpoint'leri devre dışı)
		private String key = "";
	}

	// İçerik üretimi yapılandırması
	private Content content = new Content();

	// AWS S3 yapılandırması (görsel depolama)
	private Aws aws = new Aws();

	// FAZ PAYMENT: ödeme sistemi yapılandırması
	private Payment payment = new Payment();

	/**
	 * İçerik üretimi yapılandırması.
	 * Rapor bazlı görsel + caption üretim kuyruğu ayarları.
	 */
	@Getter
	@Setter
	public static class Content {
		// İçerik üretim kuyruğu adı
		private String queue = "spectiqs.content.queue";
		// İçerik exchange adı
		private String exchange = "spectiqs.content.exchange";
		// Routing-key
		private String routingKey = "spectiqs.content";
		// Maksimum düzenleme hakkı
		private int editLimit = 3;
		// Görsel üretim için Gemini modeli (responseModalities: IMAGE desteklemeli)
		private String imageModel = "gemini-2.0-flash-preview-image-generation";
		// Tarife (TL) — PAYMENT_ENABLED=true olduğunda uygulanır
		private int pricePost = 100;
		private int priceStory = 100;
		private int priceCarousel = 120;
		private int priceReel = 150;
		private int priceAll = 300;
	}

	/**
	 * AWS S3 yapılandırması.
	 * Üretilen görseller S3'e yüklenir; public URL döner.
	 * Key'ler boşsa S3 yükleme atlanır (URL null kalır).
	 */
	@Getter
	@Setter
	public static class Aws {
		private String region = "eu-central-1";
		private String bucket = "trendora-content";
		private String accessKeyId;
		private String secretAccessKey;
	}

	/**
	 * Ödeme kapısı yapılandırması.
	 * PAYMENT_ENABLED=false yapılırsa bakiye/PayTR akışı tamamen atlanır;
	 * rapor isteği ücretsiz ve doğrudan oluşturulur (test/geliştirme kolaylığı).
	 */
	@Getter
	@Setter
	public static class Payment {
		// Ödeme kapısı aktif mi? (env: PAYMENT_ENABLED; varsayılan true)
		private boolean enabled = true;
	}
}
