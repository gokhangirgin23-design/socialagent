package com.api.local;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.api.ai.AiAnalysisService;
import com.api.config.AppProperties;
import com.api.entity.SocialPost;

import lombok.extern.slf4j.Slf4j;

/**
 * LOCAL profil için AiAnalysisService yerine geçen dummy servis (sadece iç test).
 * Gerçek AiAnalysisService'ten TÜRER; OpenAI ve Gemini'ye giden public metodları override eder.
 *
 * Kapsanan gerçek çağrılar:
 *   - analyzeFull(): OpenAI metrik analizi + Gemini görsel analizi
 *   - generateReport(): OpenAI rapor üretimi
 *
 * Birleşik analiz çıktısı, gerçek servisle BİREBİR aynı zarfta döner:
 *   {"metrics": <openai-dummy>, "visual": <gemini-dummy>}
 *
 * Failure enjeksiyonu (LocalFailMode), analyzeFull üzerinde:
 *   - AI_RETURN_NULL (count'lu): sıradaki N post için null döner -> her iki model timeout
 *     simülasyonu -> o postlar atlanır -> PARTIAL. ("18/20" için count=1.)
 *   - AI_THROW (count'lu): sıradaki N post için exception fırlatır -> analyzeRequest
 *     (@Transactional) rollback + processRequest FAILED.
 *
 * @Primary + @Profile("local"): yalnızca local'de devreye girer; diğer ortamlarda gerçek servis çalışır.
 */
@Slf4j
@Service
@Profile("local")
@Primary
public class LocalDummyAiAnalysisService extends AiAnalysisService {

	// Dummy yanıt havuzu
	private final DummyResponseProvider dummy;

	// Failure enjeksiyon anahtarı
	private final LocalFailMode failMode;

	// Üst sınıfın zorunlu bağımlılığı (AppProperties) super'a iletilir.
	public LocalDummyAiAnalysisService(AppProperties appProperties, DummyResponseProvider dummy, LocalFailMode failMode) {
		super(appProperties);
		this.dummy = dummy;
		this.failMode = failMode;
	}

	/**
	 * OpenAI (metrik) + Gemini (görsel) çağrılarını taklit eder; birleşik JSON döner.
	 * Failure modu aktifse null döner (post atlanır) ya da exception fırlatır.
	 */
	@Override
	public String analyzeFull(SocialPost post) {
		// Failure enjeksiyonu (post bazında count tüketilir)
		if (failMode.fire(LocalFailMode.Mode.AI_THROW)) {
			log.info("[LOCAL-DUMMY] AI_THROW enjekte edildi: postId={}", post.getSocialPostId());
			throw new RuntimeException("dummy AI failure (AI_THROW)");
		}
		if (failMode.fire(LocalFailMode.Mode.AI_RETURN_NULL)) {
			log.info("[LOCAL-DUMMY] AI_RETURN_NULL enjekte edildi (post atlanacak): postId={}",
					post.getSocialPostId());
			return null;
		}

		String metrics = dummy.randomMetricsJson();
		String visual = dummy.randomVisualJson();
		log.info("[LOCAL-DUMMY] AI analizi taklit edildi: postId={}", post.getSocialPostId());
		// Gerçek servisle aynı zarf
		return "{\"metrics\":" + metrics + ",\"visual\":" + visual + "}";
	}

	/**
	 * OpenAI rapor üretimini taklit eder; rastgele dummy Markdown döner.
	 */
	@Override
	public String generateReport(String prompt) {
		String markdown = dummy.randomReportMarkdown();
		log.info("[LOCAL-DUMMY] Rapor üretimi taklit edildi (uzunluk={})", markdown.length());
		return markdown;
	}
}
