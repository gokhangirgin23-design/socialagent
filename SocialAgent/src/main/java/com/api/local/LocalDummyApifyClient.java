package com.api.local;

import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyPost;
import com.api.apify.ApifyProfile;
import com.api.config.AppProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * LOCAL profil için ApifyClient yerine geçen dummy istemci (sadece iç test).
 * Gerçek ApifyClient'tan TÜRER ve dış HTTP çağrısı yapan public metodları override eder;
 * böylece pipeline'ın geri kalanı (ScrapePipelineService vb.) hiç değişmeden çalışır.
 *
 * @Primary + @Profile("local"): yalnızca local profilde devreye girer ve tip bazlı
 * enjeksiyonlarda gerçek ApifyClient yerine bu bean seçilir. Diğer ortamlarda bu sınıf
 * hiç oluşturulmaz -> gerçek ApifyClient aynen çalışır.
 *
 * Failure enjeksiyonu (LocalFailMode):
 *   - APIFY_EMPTY: boş liste döner (gerçek Apify timeout simülasyonu -> 0 post)
 *   - APIFY_THROW: exception fırlatır (beklenmedik scrape hatası -> processRequest FAILED)
 *
 * Not: Service interface yok (CLAUDE.md Madde 1) olduğundan değiştirme için kalıtım kullanıldı.
 */
@Slf4j
@Component
@Profile("local")
@Primary
public class LocalDummyApifyClient extends ApifyClient {

	// Dummy yanıt havuzu
	private final DummyResponseProvider dummy;

	// Failure enjeksiyon anahtarı
	private final LocalFailMode failMode;

	// Üst sınıfın zorunlu bağımlılığı (AppProperties) super'a iletilir.
	public LocalDummyApifyClient(AppProperties appProperties, DummyResponseProvider dummy, LocalFailMode failMode) {
		super(appProperties);
		this.dummy = dummy;
		this.failMode = failMode;
	}

	/**
	 * Gerçek Apify post çekme yerine dummy post döner. URL içeriği önemsizdir;
	 * yalnızca kaç post üretileceği (resultsLimit) kullanılır.
	 * Failure modu aktifse boş döner ya da exception fırlatır.
	 */
	@Override
	public List<ApifyPost> fetchPostsByUrls(List<String> directUrls, int resultsLimit) {
		// Failure enjeksiyonu
		if (failMode.fire(LocalFailMode.Mode.APIFY_THROW)) {
			log.info("[LOCAL-DUMMY] APIFY_THROW enjekte edildi");
			throw new RuntimeException("dummy Apify failure (APIFY_THROW)");
		}
		if (failMode.fire(LocalFailMode.Mode.APIFY_EMPTY)) {
			log.info("[LOCAL-DUMMY] APIFY_EMPTY enjekte edildi (boş döndü)");
			return List.of();
		}

		List<ApifyPost> posts = dummy.randomPosts(resultsLimit);
		log.info("[LOCAL-DUMMY] Apify post çekme taklit edildi: urlSayısı={}, üretilen={}",
				(directUrls != null ? directUrls.size() : 0), posts.size());
		return posts;
	}

	/**
	 * Profil araması bu pipeline'da kullanılmıyor; gerçek çağrıyı kesin olarak engellemek için
	 * (APIFY_TOKEN local'de set edilse bile) override edilip boş liste döndürülür.
	 */
	@Override
	public List<ApifyProfile> searchTopProfiles(String keyword, int limit) {
		log.info("[LOCAL-DUMMY] Apify profil araması taklit edildi (boş döndü): keyword={}", keyword);
		return List.of();
	}
}
