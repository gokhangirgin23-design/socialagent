package com.api.apify;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.api.config.AppProperties;
import com.api.entity.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Apify REST istemcisi (FAZ 5 — CLAUDE.md Bölüm 10, D1).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Component.
 *
 * İki işlevi vardır:
 *  1) searchTopProfiles: sektör/alt-sektör adını keyword olarak verip profil arar,
 *     follower/engagement'a göre sıralayıp ilk N profili döndürür.
 *  2) fetchRecentPosts: bir hesabın son N gönderisini çeker.
 *
 * Apify "run-sync-get-dataset-items" senkron uç noktası kullanılır:
 *   POST {baseUrl}/acts/{actorId}/run-sync-get-dataset-items
 *   Authorization: Bearer {token}  (token URL'de değil, header'da — loglara düşmez)
 * Aktör girdisi JSON gövde ile gönderilir; sonuç dataset item dizisi olarak döner.
 *
 * NOT(uyum): Aktör girdi/çıktı şemaları aktöre göre değişir. Alan adları savunmacı
 * (birden çok olası ad) okunur; gerçek aktör seçilince // TODO(uyum) notları gözden geçirilmeli.
 *
 * Token boşsa (local/dev) çağrı yapılmaz, boş liste döner — uygulama patlamaz.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApifyClient {

	// Apify ayarları (token, base-url, actor id'leri, limitler, timeout)
	private final AppProperties appProperties;

	// JSON ayrıştırma: ObjectMapper thread-safe, doğrudan oluşturuluyor
	// (Spring Boot 4'te context'ten enjekte etmek yerine yerel örnek kullanılır)
	private final ObjectMapper objectMapper = new ObjectMapper();

	// Tembel kurulan RestClient (timeout'lar config'ten)
	private RestClient restClient;

	/**
	 * Bean kurulduktan sonra RestClient'ı timeout'larla hazırlar.
	 */
	@PostConstruct
	void init() {
		AppProperties.Apify cfg = appProperties.getApify();
		// Connect + read timeout ayarlı request factory
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		// Bağlantı kurma süresi (sabit 10 sn)
		factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
		// Okuma süresi (run-sync uzun sürebilir) — config'ten
		factory.setReadTimeout((int) Duration.ofSeconds(cfg.getTimeoutSeconds()).toMillis());
		// Base URL + factory ile istemciyi kur
		this.restClient = RestClient.builder()
				.baseUrl(cfg.getBaseUrl())
				.requestFactory(factory)
				.build();
		log.info("ApifyClient hazır: baseUrl={}, timeoutSeconds={}", cfg.getBaseUrl(), cfg.getTimeoutSeconds());
	}

	/**
	 * Verilen keyword (sektör/alt-sektör adı) ile profil arar ve top N'i döndürür (D1).
	 * follower (birincil) + engagement (ikincil) azalan sıralama; ilk N alınır.
	 *
	 * @param keyword arama anahtar kelimesi (sektör/alt-sektör adı)
	 * @param limit   döndürülecek maksimum profil sayısı (top 5)
	 * @return sıralanmış en iyi profiller; token yoksa/hata olursa boş liste
	 */
	public List<ApifyProfile> searchTopProfiles(String keyword, int limit) {
		// Token yoksa çağrı yapma (local/dev güvenliği)
		if (!hasToken()) {
			log.warn("Apify token tanımsız; profil araması atlandı (keyword={}).", keyword);
			return List.of();
		}
		AppProperties.Apify cfg = appProperties.getApify();
		// Aktör girdisi (savunmacı: en yaygın alanlar). TODO(uyum): seçilen aktöre göre düzelt.
		Map<String, Object> input = Map.of(
				"search", keyword,
				"searchType", "user",
				// Aramada makul bir havuz çek; sıralamayı biz yaparız
				"searchLimit", Math.max(limit * 4, 20));
		// Aktörü çağır
		JsonNode items = runActor(cfg.getProfileActorId(), input);
		if (items == null || !items.isArray()) {
			return List.of();
		}
		// Dataset item'larını ApifyProfile'a çevir
		List<ApifyProfile> profiles = new ArrayList<>();
		for (JsonNode node : items) {
			// Hesap adı (farklı aktörlerde farklı alan adı)
			String accountName = firstText(node, "username", "ownerUsername", "account", "handle");
			// Hesap adı yoksa bu kaydı atla
			if (accountName == null || accountName.isBlank()) {
				continue;
			}
			// Takipçi sayısı
			long followers = firstLong(node, "followersCount", "followers", "followerCount", "edge_followed_by");
			// Etkileşim oranı (varsa)
			double engagement = firstDouble(node, "engagementRate", "engagement", "avgEngagement");
			// Profil URL'i
			String url = firstText(node, "url", "profileUrl", "inputUrl");
			profiles.add(new ApifyProfile(accountName, url, followers, engagement));
		}
		// follower desc, eşitlikte engagement desc sırala
		profiles.sort(Comparator
				.comparingLong(ApifyProfile::followerCount).reversed()
				.thenComparing(Comparator.comparingDouble(ApifyProfile::engagementRate).reversed()));
		// İlk N'i döndür
		List<ApifyProfile> top = profiles.subList(0, Math.min(limit, profiles.size()));
		log.info("Apify profil araması: keyword={}, bulunan={}, top={}", keyword, profiles.size(), top.size());
		// subList view yerine bağımsız kopya döndür
		return new ArrayList<>(top);
	}

	/**
	 * Instagram URL listesinden (profil veya hashtag explore sayfası) gönderi çeker.
	 * directUrls yaklaşımı: hem hesap sayfaları hem hashtag explore URL'leri desteklenir.
	 *
	 * URL örnekleri:
	 *   Hesap   : https://www.instagram.com/kullaniciadi/
	 *   Hashtag : https://www.instagram.com/explore/tags/makyaj/
	 *
	 * @param directUrls   çekilecek Instagram URL listesi
	 * @param resultsLimit URL başına çekilecek maksimum gönderi sayısı
	 * @return gönderi listesi; token yoksa/hata olursa boş liste
	 */
	public List<ApifyPost> fetchPostsByUrls(List<String> directUrls, int resultsLimit) {
		if (directUrls == null || directUrls.isEmpty()) {
			return List.of();
		}
		// Token yoksa çağrı yapma
		if (!hasToken()) {
			log.warn("Apify token tanımsız; post çekme atlandı (urlSayısı={}).", directUrls.size());
			return List.of();
		}
		AppProperties.Apify cfg = appProperties.getApify();
		// apify~instagram-post-scraper "username" alanını zorunlu tutar; URL'den çıkar
		List<String> usernames = directUrls.stream()
				.map(ApifyClient::extractUsernameFromUrl)
				.filter(u -> u != null && !u.isBlank())
				.collect(java.util.stream.Collectors.toList());
		// resultsType kaldırıldı — aktör bu parametreyi tanımıyor, 0 post dönüyordu
		Map<String, Object> input = new java.util.LinkedHashMap<>();
		input.put("directUrls", directUrls);
		input.put("username", usernames);
		input.put("resultsLimit", resultsLimit);
		log.info("Apify post input: urls={}, usernames={}", directUrls, usernames);
		// Aktörü çağır
		JsonNode items = runActor(cfg.getPostActorId(), input);
		if (items == null || !items.isArray()) {
			return List.of();
		}
		// Dataset item'larını ApifyPost'a çevir
		List<ApifyPost> posts = new ArrayList<>();
		for (JsonNode node : items) {
			// Platforma özgü gönderi kimliği (shortCode tercih, yoksa id)
			String postId = firstText(node, "shortCode", "shortcode", "id", "postId");
			// Kimlik yoksa dedup yapılamaz -> atla
			if (postId == null || postId.isBlank()) {
				continue;
			}
			// Gönderiyi paylaşan hesabın kullanıcı adı (SECTOR postlarında sector_account_name)
			String ownerUsername = firstText(node, "ownerUsername", "username", "owner", "handle");
			String url = firstText(node, "url", "postUrl", "permalink");
			String caption = firstText(node, "caption", "text", "title");
			// Hashtag'ler dizi olabilir -> "#a #b" formatına getir
			String hashtags = extractHashtags(node, caption);
			String mediaUrl = firstText(node, "displayUrl", "imageUrl", "videoUrl", "thumbnailUrl");
			// Ham medya tipini kanonik MediaType'a normalize et
			String rawType = firstText(node, "type", "productType", "mediaType", "__typename");
			String mediaType = com.api.entity.MediaType.fromRaw(rawType).name();
			Long likes = firstLongOrNull(node, "likesCount", "likes", "edge_liked_by");
			Long comments = firstLongOrNull(node, "commentsCount", "comments", "edge_media_to_comment");
			Long views = firstLongOrNull(node, "videoViewCount", "viewsCount", "views", "videoPlayCount");
			Long shares = firstLongOrNull(node, "sharesCount", "shares", "reshareCount");
			LocalDateTime postDate = extractTimestamp(node, "timestamp", "takenAt", "taken_at_timestamp");
			// Ham JSON node'unu metin olarak sakla (result_json kolonuna gidecek)
			String rawJson = node.toString();
			posts.add(new ApifyPost(postId, ownerUsername, url, caption, hashtags, mediaUrl, mediaType,
					likes, comments, views, shares, postDate, rawJson));
		}
		log.info("Apify post çekme (directUrls): urlSayısı={}, toplamGelen={}", directUrls.size(), posts.size());
		return posts;
	}

	/**
	 * Bir hesabın son N gönderisini kullanıcı adı ile çeker.
	 * Geriye uyum için korunur; içeride fetchPostsByUrls'e delege eder.
	 *
	 * @param accountName hesap kullanıcı adı
	 * @param limit       çekilecek maksimum gönderi sayısı
	 * @return gönderi listesi; token yoksa/hata olursa boş liste
	 */
	public List<ApifyPost> fetchRecentPosts(String accountName, int limit) {
		String profileUrl = "https://www.instagram.com/" + accountName + "/";
		return fetchPostsByUrls(List.of(profileUrl), limit);
	}

	// ============================================================
	// Yardımcılar
	// ============================================================

	/**
	 * Apify aktörünü senkron çalıştırır ve dataset item dizisini JsonNode olarak döndürür.
	 * Hata durumunda null döner (çağıran boş liste ile devam eder).
	 */
	private JsonNode runActor(String actorId, Map<String, Object> input) {
		try {
			// run-sync-get-dataset-items uç noktası
			// Token URL query param yerine Authorization header'ında taşınır;
			// böylece HTTP access log'larına ve exception trace'lerine düşmez.
			String path = "/acts/" + actorId + "/run-sync-get-dataset-items";
			String bearerToken = "Bearer " + appProperties.getApify().getToken();
			// POST + JSON gövde -> ham JSON yanıt
			String body = restClient.post()
					.uri(path)
					.header("Authorization", bearerToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(input)
					.retrieve()
					.body(String.class);
			// Yanıtı JsonNode'a ayrıştır
			return (body == null || body.isBlank()) ? null : objectMapper.readTree(body);
		} catch (Exception ex) {
			// Ağ/format/aktör hatası -> logla, null dön (worker dayanıklı kalır)
			log.error("Apify aktör çağrısı başarısız: actorId={}, hata={}", actorId, ex.getMessage());
			return null;
		}
	}

	/**
	 * Token tanımlı mı? (boş/null değilse true)
	 */
	private boolean hasToken() {
		String token = appProperties.getApify().getToken();
		return token != null && !token.isBlank();
	}

	/**
	 * Verilen alan adlarından ilk dolu olanın metin değerini döndürür; yoksa null.
	 * İç içe sayısal nesneler (ör. edge_followed_by.count) için count alanına da bakar.
	 */
	private String firstText(JsonNode node, String... fields) {
		for (String f : fields) {
			JsonNode v = node.get(f);
			if (v != null && !v.isNull() && v.isValueNode() && !v.asText().isBlank()) {
				return v.asText();
			}
		}
		return null;
	}

	/**
	 * Verilen alanlardan ilk dolu sayıyı long olarak döndürür; yoksa 0.
	 * "edge_*" gibi { "count": N } yapısındaki nesneleri de okur.
	 */
	private long firstLong(JsonNode node, String... fields) {
		Long v = firstLongOrNull(node, fields);
		return v != null ? v : 0L;
	}

	/**
	 * Verilen alanlardan ilk dolu sayıyı Long olarak döndürür; yoksa null.
	 */
	private Long firstLongOrNull(JsonNode node, String... fields) {
		for (String f : fields) {
			JsonNode v = node.get(f);
			if (v == null || v.isNull()) {
				continue;
			}
			// Doğrudan sayısal değer
			if (v.isNumber()) {
				return v.asLong();
			}
			// { "count": N } yapısı (Instagram GraphQL edge'leri)
			if (v.isObject() && v.has("count") && v.get("count").isNumber()) {
				return v.get("count").asLong();
			}
			// Sayısal metin
			if (v.isTextual()) {
				try {
					return Long.parseLong(v.asText().trim());
				} catch (NumberFormatException ignore) {
					// metin sayı değil; sıradaki alana geç
				}
			}
		}
		return null;
	}

	/**
	 * Verilen alanlardan ilk dolu ondalık değeri double olarak döndürür; yoksa 0.0.
	 */
	private double firstDouble(JsonNode node, String... fields) {
		for (String f : fields) {
			JsonNode v = node.get(f);
			if (v != null && v.isNumber()) {
				return v.asDouble();
			}
		}
		return 0.0;
	}

	/**
	 * Hashtag'leri çıkarır: önce "hashtags" dizisi, yoksa caption içinden '#' kelimeleri.
	 * Sonuç "#tag1 #tag2" formatında; hiç yoksa null.
	 */
	private String extractHashtags(JsonNode node, String caption) {
		// 1) Aktör hashtags dizisi sağlıyorsa onu kullan
		JsonNode tags = node.get("hashtags");
		if (tags != null && tags.isArray() && !tags.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			Iterator<JsonNode> it = tags.elements();
			while (it.hasNext()) {
				String t = it.next().asText().trim();
				if (t.isBlank()) {
					continue;
				}
				// Başında '#' yoksa ekle
				sb.append(t.startsWith("#") ? t : "#" + t).append(' ');
			}
			String result = sb.toString().trim();
			return result.isBlank() ? null : result;
		}
		// 2) Caption'dan '#' ile başlayan kelimeleri topla
		if (caption != null && caption.contains("#")) {
			StringBuilder sb = new StringBuilder();
			for (String word : caption.split("\\s+")) {
				if (word.startsWith("#") && word.length() > 1) {
					sb.append(word).append(' ');
				}
			}
			String result = sb.toString().trim();
			return result.isBlank() ? null : result;
		}
		// Hashtag yok
		return null;
	}

	/**
	 * Zaman damgasını LocalDateTime'a çevirir.
	 * Destekler: epoch saniye (number), ISO-8601 metin. Çözümlenemezse null.
	 */
	private LocalDateTime extractTimestamp(JsonNode node, String... fields) {
		for (String f : fields) {
			JsonNode v = node.get(f);
			if (v == null || v.isNull()) {
				continue;
			}
			try {
				// Epoch saniye (Instagram taken_at_timestamp gibi)
				if (v.isNumber()) {
					long epochSeconds = v.asLong();
					return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
				}
				// ISO-8601 metin (ör. "2026-01-15T10:30:00.000Z")
				if (v.isTextual()) {
					Instant instant = Instant.parse(v.asText().trim());
					return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
				}
			} catch (Exception ignore) {
				// Bu alan çözümlenemedi; sıradakini dene
			}
		}
		return null;
	}

	/**
	 * Instagram profil URL'sinden kullanıcı adını çıkarır.
	 * "https://www.instagram.com/hesap_adi/" → "hesap_adi"
	 * apify~instagram-post-scraper "username" alanını zorunlu tuttuğundan gerekli.
	 */
	private static String extractUsernameFromUrl(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		// Sondaki slash ve boşlukları temizle
		String u = url.trim().replaceAll("/+$", "");
		int idx = u.lastIndexOf('/');
		return idx >= 0 ? u.substring(idx + 1) : null;
	}

	/**
	 * Şu an yalnızca Instagram destekleniyor (CLAUDE.md Bölüm 6).
	 * İleride çoklu platform için aktör seçimi burada genişletilir.
	 */
	public String defaultPlatform() {
		return Platform.INSTAGRAM.name();
	}
}
