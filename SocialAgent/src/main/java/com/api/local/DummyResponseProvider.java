package com.api.local;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import com.api.apify.ApifyPost;
import com.api.entity.MediaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * LOCAL profil dummy yanıt sağlayıcı (sadece iç test).
 * Apify / OpenAI / Gemini gerçek çağrıları yerine, classpath altındaki text dosyalarından
 * okunan havuzlardan RASTGELE yanıt üretir. Hiçbir gerçek HTTP çağrısı yapılmaz.
 *
 * Tasarım notu (öneri uygulandı): "satır bazlı 10 response" yerine her kanal,
 * çok satırlı JSON'ı güvenle taşıyabilmesi için JSON dizisi (rapor için ===SEP=== ile
 * ayrılmış Markdown) olarak tutulur. Böylece parse hatasız ve genişletilebilir olur.
 *
 * Kaynak dosyalar (her biri ~10 adet):
 *   - dummy/apify-posts.json     : Apify post şablonları  (ApifyClient.fetchPostsByUrls)
 *   - dummy/openai-metrics.json  : OpenAI metrik analizi   (AiAnalysisService.analyzeFull -> metrics)
 *   - dummy/gemini-visual.json   : Gemini görsel analizi   (AiAnalysisService.analyzeFull -> visual)
 *   - dummy/openai-hashtags.json : OpenAI sektör hashtag'i (HashtagService.resolveExploreUrls)
 *   - dummy/openai-reports.md    : OpenAI rapor üretimi    (AiAnalysisService.generateReport)
 *
 * Bir dosya okunamaz/parse edilemezse uygulama PATLAMAZ; küçük gömülü fallback kullanılır
 * (projenin "graceful degradation" felsefesiyle aynı).
 */
@Slf4j
@Component
@Profile("local")
public class DummyResponseProvider {

	// Rapor markdown'larını ayıran işaret (openai-reports.md içinde)
	private static final String REPORT_SEPARATOR = "===SEP===";

	// JSON parse için (thread-safe)
	private final ObjectMapper mapper = new ObjectMapper();

	// Yüklenen havuzlar
	private List<JsonNode> apifyPostTemplates = new ArrayList<>();
	private List<JsonNode> metricTemplates = new ArrayList<>();
	private List<JsonNode> visualTemplates = new ArrayList<>();
	private List<String> hashtagPool = new ArrayList<>();
	private List<String> reportPool = new ArrayList<>();

	/**
	 * Context açılışında tüm dummy havuzları classpath'ten yükler.
	 */
	@PostConstruct
	void load() {
		this.apifyPostTemplates = readJsonArray("dummy/apify-posts.json");
		this.metricTemplates = readJsonArray("dummy/openai-metrics.json");
		this.visualTemplates = readJsonArray("dummy/gemini-visual.json");
		this.hashtagPool = readStringArray("dummy/openai-hashtags.json");
		this.reportPool = readReports("dummy/openai-reports.md");
		log.info("Dummy havuzları yüklendi (LOCAL): apifyPost={}, metric={}, visual={}, hashtag={}, report={}",
				apifyPostTemplates.size(), metricTemplates.size(), visualTemplates.size(),
				hashtagPool.size(), reportPool.size());
	}

	// ============================================================
	// Apify (post çekme) dummy üretimi
	// ============================================================

	/**
	 * Verilen limit kadar rastgele dummy ApifyPost üretir.
	 * Her çağrıda TAZE rastgele platformPostId üretilir -> dedup'a takılmadan her tetiklemede
	 * yeni post insert edilebilir. Metrikler de hafifçe oynatılır ki raporlar çeşitlensin.
	 *
	 * @param resultsLimit üretilecek maksimum post sayısı (URL başına)
	 * @return dummy post listesi (asla null)
	 */
	public List<ApifyPost> randomPosts(int resultsLimit) {
		if (apifyPostTemplates.isEmpty()) {
			return List.of();
		}
		int count = Math.max(1, Math.min(resultsLimit, apifyPostTemplates.size()));

		// Şablonları karıştırıp ilk 'count' tanesini al
		List<JsonNode> shuffled = new ArrayList<>(apifyPostTemplates);
		Collections.shuffle(shuffled, ThreadLocalRandom.current());

		List<ApifyPost> posts = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			posts.add(toApifyPost(shuffled.get(i)));
		}
		return posts;
	}

	/**
	 * Tek bir JSON şablonu -> ApifyPost (taze id + oynatılmış metriklerle).
	 */
	private ApifyPost toApifyPost(JsonNode t) {
		// Taze, benzersiz platform post id (dedup'a takılmaz)
		String shortCode = randomShortCode();
		String postUrl = "https://www.instagram.com/p/" + shortCode + "/";

		String ownerUsername = text(t, "ownerUsername", "dummy_account");
		String caption = text(t, "caption", "");
		String hashtags = text(t, "hashtags", null);
		String mediaUrl = text(t, "mediaUrl", null);
		// MediaType'ı projedeki kanonik değere normalize et
		String mediaType = MediaType.fromRaw(text(t, "mediaType", "IMAGE")).name();

		// Metrikleri +/- %20 oynat (rapor çeşitliliği için)
		Long likes = jitter(t.path("likes").asLong(0));
		Long comments = jitter(t.path("comments").asLong(0));
		long rawViews = t.path("views").asLong(0);
		// IMAGE/CAROUSEL'de görüntülenme yok -> null; VIDEO'da oynatılmış değer
		Long views = rawViews > 0 ? jitter(rawViews) : null;
		Long shares = jitter(Math.max(1, (likes != null ? likes : 0) / 50));

		// Son 0-14 gün içinde rastgele yayın tarihi
		LocalDateTime postDate = LocalDateTime.now()
				.minusDays(ThreadLocalRandom.current().nextInt(0, 15))
				.minusHours(ThreadLocalRandom.current().nextInt(0, 24));

		// result_json: gerçek Apify ham JSON taklidi (OpenAI metrik promptuna gider — local'de dummy okur)
		String rawJson = buildRawJson(shortCode, ownerUsername, mediaType, caption,
				likes, comments, views);

		return new ApifyPost(shortCode, ownerUsername, postUrl, caption, hashtags, mediaUrl,
				mediaType, likes, comments, views, shares, postDate, rawJson);
	}

	// ============================================================
	// OpenAI / Gemini dummy üretimi
	// ============================================================

	/** OpenAI metrik analizi: rastgele bir metrik JSON nesnesi (string). */
	public String randomMetricsJson() {
		if (metricTemplates.isEmpty()) {
			return "{\"engagementLevel\":\"ORTA\",\"contentType\":{\"isReel\":false}}";
		}
		return pickRandom(metricTemplates).toString();
	}

	/** Gemini görsel analizi: rastgele bir görsel JSON nesnesi (string). */
	public String randomVisualJson() {
		if (visualTemplates.isEmpty()) {
			return "{\"hasHuman\":false,\"hasModel\":false,\"isProductFocused\":true}";
		}
		return pickRandom(visualTemplates).toString();
	}

	/** OpenAI rapor üretimi: rastgele bir Markdown rapor. */
	public String randomReportMarkdown() {
		if (reportPool.isEmpty()) {
			return "# Dummy Rapor\n\nLocal test raporu (havuz boş).";
		}
		return pickRandom(reportPool);
	}

	/**
	 * OpenAI sektör hashtag araması: havuzdan rastgele 'count' adet etiket döndürür.
	 * HashtagService bunları explore URL'lerine çevirir.
	 */
	public List<String> randomHashtags(int count) {
		if (hashtagPool.isEmpty()) {
			return List.of("moda", "stil", "trend");
		}
		List<String> shuffled = new ArrayList<>(hashtagPool);
		Collections.shuffle(shuffled, ThreadLocalRandom.current());
		return new ArrayList<>(shuffled.subList(0, Math.min(count, shuffled.size())));
	}

	// ============================================================
	// Yardımcılar
	// ============================================================

	/** +/- %20 rastgele oynatma (en az 0). */
	private Long jitter(long base) {
		if (base <= 0) {
			return 0L;
		}
		double factor = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4; // 0.8 - 1.2
		return Math.max(0L, Math.round(base * factor));
	}

	/** 11 karakterlik rastgele Instagram benzeri shortcode. */
	private String randomShortCode() {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
		StringBuilder sb = new StringBuilder(11);
		for (int i = 0; i < 11; i++) {
			sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
		}
		return sb.toString();
	}

	/** result_json için küçük ham JSON taklidi üretir. */
	private String buildRawJson(String shortCode, String owner, String mediaType, String caption,
			Long likes, Long comments, Long views) {
		try {
			var node = mapper.createObjectNode();
			node.put("shortCode", shortCode);
			node.put("ownerUsername", owner);
			node.put("type", mediaType);
			node.put("caption", caption);
			node.put("likesCount", likes != null ? likes : 0);
			node.put("commentsCount", comments != null ? comments : 0);
			if (views != null) {
				node.put("videoViewCount", views);
			}
			node.put("source", "LOCAL_DUMMY");
			return mapper.writeValueAsString(node);
		} catch (Exception ex) {
			// JSON üretimi başarısız olsa bile pipeline durmasın
			return "{\"source\":\"LOCAL_DUMMY\",\"shortCode\":\"" + shortCode + "\"}";
		}
	}

	/** Bir JsonNode'dan metin alan okur; yoksa varsayılan. */
	private String text(JsonNode node, String field, String def) {
		JsonNode v = node.get(field);
		return (v != null && !v.isNull() && !v.asText().isBlank()) ? v.asText() : def;
	}

	/** Listeden rastgele eleman seçer. */
	private <T> T pickRandom(List<T> list) {
		return list.get(ThreadLocalRandom.current().nextInt(list.size()));
	}

	/** classpath JSON dizisini JsonNode listesine okur. */
	private List<JsonNode> readJsonArray(String path) {
		try {
			JsonNode root = mapper.readTree(readResource(path));
			List<JsonNode> out = new ArrayList<>();
			if (root.isArray()) {
				root.forEach(out::add);
			}
			return out;
		} catch (Exception ex) {
			log.warn("Dummy dosyası okunamadı (JSON dizi): {}, hata={}", path, ex.getMessage());
			return new ArrayList<>();
		}
	}

	/** classpath JSON string dizisini String listesine okur. */
	private List<String> readStringArray(String path) {
		try {
			JsonNode root = mapper.readTree(readResource(path));
			List<String> out = new ArrayList<>();
			if (root.isArray()) {
				root.forEach(n -> out.add(n.asText()));
			}
			return out;
		} catch (Exception ex) {
			log.warn("Dummy dosyası okunamadı (String dizi): {}, hata={}", path, ex.getMessage());
			return new ArrayList<>();
		}
	}

	/** Markdown raporları ===SEP=== işaretine göre böler. */
	private List<String> readReports(String path) {
		try {
			String content = readResource(path);
			List<String> out = new ArrayList<>();
			for (String part : content.split(REPORT_SEPARATOR)) {
				String trimmed = part.strip();
				if (!trimmed.isBlank()) {
					out.add(trimmed);
				}
			}
			return out;
		} catch (Exception ex) {
			log.warn("Dummy rapor dosyası okunamadı: {}, hata={}", path, ex.getMessage());
			return new ArrayList<>();
		}
	}

	/** classpath kaynağını UTF-8 metin olarak okur. */
	private String readResource(String path) throws IOException {
		try (InputStream is = new ClassPathResource(path).getInputStream()) {
			return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
		}
	}
}
