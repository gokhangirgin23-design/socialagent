package com.api.local;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.api.config.AppProperties;
import com.api.service.HashtagService;

import lombok.extern.slf4j.Slf4j;

/**
 * LOCAL profil için HashtagService yerine geçen dummy servis (sadece iç test).
 * Gerçek HashtagService kendi içinde OpenAI'ya hashtag sorar; local'de bu çağrı taklit edilir.
 *
 * resolveExploreUrls() override edilerek dummy hashtag havuzundan Instagram explore tag
 * URL'leri üretilir. Böylece NONE / OWN_ONLY modlarında SECTOR hedefleri de oluşur ve
 * pipeline uçtan uca (sektör dahil) test edilebilir.
 *
 * @Primary + @Profile("local"): yalnızca local'de devreye girer; diğer ortamlarda gerçek servis çalışır.
 */
@Slf4j
@Service
@Profile("local")
@Primary
public class LocalDummyHashtagService extends HashtagService {

	private static final String BASE_TAG_URL = "https://www.instagram.com/explore/tags/";
	private static final int TOP_HASHTAG_COUNT = 5;

	// Dummy yanıt havuzu
	private final DummyResponseProvider dummy;

	// Üst sınıfın zorunlu bağımlılıkları (JdbcTemplate, AppProperties) super'a iletilir.
	public LocalDummyHashtagService(JdbcTemplate jdbcTemplate, AppProperties appProperties,
			DummyResponseProvider dummy) {
		super(jdbcTemplate, appProperties);
		this.dummy = dummy;
	}

	/**
	 * OpenAI hashtag araması yerine dummy havuzdan 5 etiket seçip explore URL'leri üretir.
	 */
	@Override
	public List<String> resolveExploreUrls(UUID userId) {
		List<String> tags = dummy.randomHashtags(TOP_HASHTAG_COUNT);
		List<String> urls = new ArrayList<>();
		for (String tag : tags) {
			String clean = tag.replace("#", "").trim().replace(" ", "");
			if (!clean.isBlank()) {
				urls.add(BASE_TAG_URL + clean + "/");
			}
		}
		log.info("[LOCAL-DUMMY] Hashtag/explore URL üretimi taklit edildi: userId={}, url={}",
				userId, urls.size());
		return urls;
	}
}
