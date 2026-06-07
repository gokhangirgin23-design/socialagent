package com.api.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.api.config.AppProperties;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sektör hashtag servisi (WorkerPrompt — a-Apify Sektör Araştırması).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Akış:
 *   1) userId'den subsector (yoksa sector) adını çek (JdbcTemplate native).
 *   2) OpenAI'a sektör adıyla top 5 Instagram hashtag'i sor.
 *   3) Her hashtag için Instagram explore tag URL'i üret:
 *      https://www.instagram.com/explore/tags/{tag}/
 *
 * Bu URL listesi TargetResolver tarafından NONE ve OWN_ONLY modlarında
 * SECTOR tipi ScrapeTarget oluşturmak için kullanılır.
 *
 * OpenAI modeli AppProperties üzerinden kurulur; key yoksa boş liste döner (uygulama çökmez).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HashtagService {

	private static final String BASE_TAG_URL = "https://www.instagram.com/explore/tags/";
	private static final int TOP_HASHTAG_COUNT = 5;

	// Sektör/alt-sektör adı için JdbcTemplate native
	private final JdbcTemplate jdbcTemplate;

	// OpenAI ayarları (api-key, model adı vb.)
	private final AppProperties appProperties;

	// OpenAI metin modeli (key yoksa null; boş liste döner)
	private ChatLanguageModel openAiModel;

	@PostConstruct
	void init() {
		AppProperties.Ai.OpenAi oa = appProperties.getAi().getOpenai();
		if (!appProperties.getAi().isEnabled()) {
			log.info("AI kapalı (app.ai.enabled=false); HashtagService modeli kurulmadı.");
			return;
		}
		if (oa.getApiKey() != null && !oa.getApiKey().isBlank()) {
			this.openAiModel = OpenAiChatModel.builder()
					.apiKey(oa.getApiKey())
					.modelName(oa.getModel())
					.temperature(oa.getTemperature())
					.timeout(Duration.ofSeconds(oa.getTimeoutSeconds()))
					.build();
			log.info("HashtagService OpenAI modeli hazır: model={}", oa.getModel());
		} else {
			log.warn("OpenAI api-key tanımsız; HashtagService hashtag araması atlanacak.");
		}
	}

	/**
	 * Kullanıcının sektörüne ait top 5 Instagram hashtag explore URL'lerini döndürür.
	 *
	 * @param userId kullanıcı id'si (sektör/alt-sektör bilgisi için)
	 * @return Instagram explore tag URL listesi; model yoksa/hata olursa boş liste
	 */
	public List<String> resolveExploreUrls(UUID userId) {
		// 1) Sektör/alt-sektör adını belirle
		String keyword = resolveKeyword(userId);
		if (keyword == null || keyword.isBlank()) {
			log.warn("HashtagService: sektör keyword belirlenemedi, userId={}", userId);
			return List.of();
		}
		// 2) OpenAI'dan top 5 hashtag al
		List<String> hashtags = fetchHashtagsFromOpenAi(keyword);
		if (hashtags.isEmpty()) {
			return List.of();
		}
		// 3) Her hashtag için explore URL oluştur
		List<String> urls = new ArrayList<>();
		for (String tag : hashtags) {
			// Baştaki '#' karakterini at, boşlukları kaldır
			String clean = tag.replace("#", "").trim().replace(" ", "");
			if (!clean.isBlank()) {
				urls.add(BASE_TAG_URL + clean + "/");
			}
		}
		log.info("HashtagService: keyword={}, hashtag={}, url={}", keyword, hashtags.size(), urls.size());
		return urls;
	}

	/**
	 * userId'den subsector (önce) veya sector (yoksa) adını döndürür.
	 */
	private String resolveKeyword(UUID userId) {
		// Kullanıcının sektör/alt-sektör id'lerini çek
		String refSql = """
				SELECT sector_id, subsector_id
				FROM user_info
				WHERE user_id = ? AND active = 1
				""";
		List<SectorRef> refs = jdbcTemplate.query(refSql, (rs, rowNum) -> new SectorRef(
				rs.getObject("sector_id", UUID.class),
				rs.getObject("subsector_id", UUID.class)), userId);

		if (refs.isEmpty() || refs.get(0).sectorId() == null) {
			return null;
		}
		SectorRef ref = refs.get(0);

		// Alt-sektör adı önce
		if (ref.subsectorId() != null) {
			String name = lookupName("subsector", "subsector_id", ref.subsectorId());
			if (name != null) {
				return name;
			}
		}
		// Yoksa sektör adı
		return lookupName("sector", "sector_id", ref.sectorId());
	}

	/**
	 * OpenAI'ya keyword vererek top N hashtag listesi döndürür.
	 * Model yanıtı "#tag1,#tag2,..." formatında beklenir; virgül veya yeni satırla ayrılır.
	 */
	private List<String> fetchHashtagsFromOpenAi(String keyword) {
		if (openAiModel == null) {
			log.debug("OpenAI modeli yok; hashtag araması atlandı.");
			return List.of();
		}
		String prompt = """
				Sen bir Instagram pazarlama uzmanısın.
				"%s" sektörü/kategorisi için Instagram'da en çok kullanılan ve en etkili %d hashtag'i belirle.
				SADECE hashtag'leri virgülle ayırarak döndür. Açıklama, numara, ek metin EKLEME.
				Örnek format: #makyaj,#ciltbakimi,#guzellik,#skincare,#makeup
				""".formatted(keyword, TOP_HASHTAG_COUNT);
		try {
			String raw = openAiModel.chat(prompt);
			return parseHashtags(raw);
		} catch (Exception ex) {
			log.error("OpenAI hashtag araması başarısız: keyword={}, hata={}", keyword, ex.getMessage());
			return List.of();
		}
	}

	/**
	 * OpenAI yanıtından hashtag listesi çıkarır.
	 * Virgül, yeni satır veya boşlukla ayrılmış "#tag" biçimlerini destekler.
	 */
	private List<String> parseHashtags(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		List<String> tags = new ArrayList<>();
		// Virgül veya yeni satırla böl
		String[] parts = raw.split("[,\\n]+");
		for (String part : parts) {
			String tag = part.trim();
			if (tag.isBlank()) {
				continue;
			}
			// '#' yoksa ekle
			if (!tag.startsWith("#")) {
				tag = "#" + tag;
			}
			tags.add(tag);
			if (tags.size() >= TOP_HASHTAG_COUNT) {
				break;
			}
		}
		return tags;
	}

	/**
	 * Tek tablodan ad (name) çeker; bulunamazsa null.
	 */
	private String lookupName(String table, String idColumn, UUID id) {
		String sql = "SELECT name FROM " + table + " WHERE " + idColumn + " = ? AND active = 1";
		List<String> names = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("name"), id);
		return names.isEmpty() ? null : names.get(0);
	}

	// Sektör/alt-sektör id taşıyıcısı (iç kullanım)
	private record SectorRef(UUID sectorId, UUID subsectorId) {
	}
}
