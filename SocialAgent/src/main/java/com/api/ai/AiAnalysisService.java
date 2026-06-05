package com.api.ai;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.api.ai.prompt.AnalysisPrompts;
import com.api.config.AppProperties;
import com.api.entity.MediaType;
import com.api.entity.SocialPost;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI analiz servisi (FAZ 6 — CLAUDE.md Bölüm 11, D3).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Yönlendirme (D3):
 *   - media_type = TEXT (veya caption ağırlıklı)        -> OpenAI metin modeli
 *   - media_type = IMAGE | VIDEO | CAROUSEL             -> Gemini Vision (görsel + metin)
 *
 * Model'ler @PostConstruct'ta kurulur (ApifyClient paterniyle aynı). İlgili API key boşsa
 * model null bırakılır; o yol için analiz atlanır ve uygulama patlamaz (local/dev güvenliği).
 * Bu yaklaşım Spring'in null-bean sorununu da önler (config'te @Bean null dönmek yerine
 * model yaşam döngüsü servisin içinde yönetilir).
 *
 * NOT(uyum): LangChain4j 1.0.x API'si baz alındı (ChatModel, chat(...) -> ChatResponse,
 * UserMessage + ImageContent multimodal). Boot 4 ile derlerken sürüm imzaları doğrulanmalı
 * (CLAUDE.md pom TODO(uyum) notuyla tutarlı); değişirse düzeltme yalnızca bu sınıftadır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

	// AI ayarları (api key'ler, model adları, sıcaklık, timeout)
	private final AppProperties appProperties;

	// OpenAI metin modeli (key yoksa null -> TEXT analizi atlanır)
	private ChatLanguageModel openAiModel;

	// Gemini görsel modeli (key yoksa null -> medya analizi atlanır)
	private ChatLanguageModel geminiModel;

	/**
	 * Bean kurulduktan sonra modelleri (varsa) hazırlar.
	 * Key boşsa ilgili model kurulmaz; log ile bildirilir.
	 */
	@PostConstruct
	void init() {
		AppProperties.Ai ai = appProperties.getAi();

		// AI tamamen kapalıysa hiç model kurma (local kolaylığı)
		if (!ai.isEnabled()) {
			log.info("AI analizi kapalı (app.ai.enabled=false); modeller kurulmadı.");
			return;
		}

		// OpenAI modeli (TEXT/caption yolu)
		AppProperties.Ai.OpenAi oa = ai.getOpenai();
		if (hasText(oa.getApiKey())) {
			// TODO(uyum): builder imzasını LangChain4j sürümüne göre doğrula
			this.openAiModel = OpenAiChatModel.builder()
					.apiKey(oa.getApiKey())
					.modelName(oa.getModel())
					.temperature(oa.getTemperature())
					.timeout(Duration.ofSeconds(oa.getTimeoutSeconds()))
					.build();
			log.info("OpenAI modeli hazır (TEXT analizi): model={}", oa.getModel());
		} else {
			log.warn("OpenAI api-key tanımsız; TEXT/caption analizi atlanacak.");
		}

		// Gemini modeli (IMAGE/VIDEO/CAROUSEL yolu)
		AppProperties.Ai.Gemini ge = ai.getGemini();
		if (hasText(ge.getApiKey())) {
			// TODO(uyum): builder imzasını LangChain4j sürümüne göre doğrula
			this.geminiModel = GoogleAiGeminiChatModel.builder()
					.apiKey(ge.getApiKey())
					.modelName(ge.getModel())
					.temperature(ge.getTemperature())
					.build();
			log.info("Gemini modeli hazır (medya analizi): model={}", ge.getModel());
		} else {
			log.warn("Gemini api-key tanımsız; IMAGE/VIDEO/CAROUSEL analizi atlanacak.");
		}
	}

	/**
	 * Bir gönderiyi medya türüne göre uygun modele yönlendirip analiz JSON'ı üretir.
	 *
	 * @param post analiz edilecek social_post
	 * @return analiz JSON metni; ilgili model yoksa/hata olursa null (çağıran kaydı atlar)
	 */
	public String analyze(SocialPost post) {
		// Medya türünü normalize et (ham string -> enum); null -> TEXT
		MediaType type = MediaType.fromRaw(post.getMediaType());

		// TEXT -> OpenAI; diğerleri -> Gemini Vision (D3)
		if (type == MediaType.TEXT) {
			return analyzeText(post);
		}
		return analyzeMedia(post, type);
	}

	/**
	 * TEXT/caption yolu: OpenAI metin modeline prompt gönderir.
	 */
	private String analyzeText(SocialPost post) {
		// Model kurulmadıysa (key yok) atla
		if (openAiModel == null) {
			log.debug("OpenAI modeli yok; TEXT analizi atlandı (postId={}).", post.getSocialPostId());
			return null;
		}
		try {
			// Prompt'u üret ve modeli çağır
			String prompt = AnalysisPrompts.forText(post);
			String raw = openAiModel.chat(prompt);
			// Modelin döndürdüğü metni JSON'a sadeleştir
			return cleanJson(raw);
		} catch (Exception ex) {
			// AI/ağ hatası -> logla, null dön (pipeline dayanıklı kalır)
			log.error("OpenAI TEXT analizi başarısız: postId={}, hata={}", post.getSocialPostId(), ex.getMessage());
			return null;
		}
	}

	/**
	 * IMAGE/VIDEO/CAROUSEL yolu: Gemini Vision'a görsel + metin gönderir.
	 * Görsel yoksa metinle (caption) devam edilir.
	 */
	private String analyzeMedia(SocialPost post, MediaType type) {
		// Model kurulmadıysa (key yok) atla
		if (geminiModel == null) {
			log.debug("Gemini modeli yok; {} analizi atlandı (postId={}).", type, post.getSocialPostId());
			return null;
		}
		try {
			// Prompt metni + (varsa) görsel içeriği birleştir
			String prompt = AnalysisPrompts.forMedia(post);
			UserMessage message;
			if (hasText(post.getMediaUrl())) {
				// Görsel URL'i ile multimodal mesaj
				// TODO(uyum): ImageContent.from(url) imzasını sürüme göre doğrula
				message = UserMessage.from(
						TextContent.from(prompt),
						ImageContent.from(post.getMediaUrl()));
			} else {
				// Görsel yoksa yalnızca metinle ilerle
				message = UserMessage.from(TextContent.from(prompt));
			}
			// Modeli çağır ve yanıt metnini al
			ChatResponse response = geminiModel.chat(message);
			String raw = response.aiMessage().text();
			return cleanJson(raw);
		} catch (Exception ex) {
			// AI/ağ hatası -> logla, null dön
			log.error("Gemini medya analizi başarısız: postId={}, hata={}", post.getSocialPostId(), ex.getMessage());
			return null;
		}
	}

	/**
	 * FAZ 7 — Rapor üretimi: verilen prompt'u OpenAI metin modeline gönderip Markdown rapor döndürür.
	 * Analiz (analyze) JSON üretirken; bu metod Markdown üretir, bu yüzden cleanJson UYGULANMAZ
	 * (aksi halde Markdown'daki '{' '}' kaybolurdu) — yalnızca dış kod bloğu sarmalaması temizlenir.
	 *
	 * Model wiring tek sınıfta toplandığından (FAZ 6 kilitli kararı) rapor da bu OpenAI modelini
	 * yeniden kullanır; olası LangChain4j sürüm farkları yalnızca bu sınıfta düzeltilir.
	 *
	 * @param prompt ReportPrompts.forJob ile üretilen rapor prompt'u
	 * @return Markdown rapor metni; model yoksa (key boş) veya hata olursa null (çağıran FAILED işaretler)
	 */
	public String generateReport(String prompt) {
		// OpenAI modeli kurulmadıysa (key yok / AI kapalı) rapor üretilemez
		if (openAiModel == null) {
			log.warn("OpenAI modeli yok; rapor üretimi atlandı (AI kapalı veya key tanımsız).");
			return null;
		}
		try {
			// Modeli çağır ve dönen Markdown'ı dış kod bloğundan arındır
			String raw = openAiModel.chat(prompt);
			return cleanMarkdown(raw);
		} catch (Exception ex) {
			// AI/ağ hatası -> logla, null dön (pipeline FAILED işaretler, çökmez)
			log.error("OpenAI rapor üretimi başarısız: hata={}", ex.getMessage());
			return null;
		}
	}

	// ============================================================
	// Yardımcılar
	// ============================================================

	/**
	 * Markdown çıktısını yalnızca dış ```-bloğu sarmalamasından arındırır.
	 * (cleanJson'dan farkı: '{' '}' arasını ALMAZ; Markdown gövdesini korur.)
	 */
	private String cleanMarkdown(String raw) {
		// Boş yanıt -> null (çağıran FAILED işaretler)
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String text = raw.trim();
		// Model bazen tüm raporu ```markdown ... ``` bloğuna sarar; dış sarmalamayı kaldır
		if (text.startsWith("```")) {
			int firstNewline = text.indexOf('\n');
			if (firstNewline >= 0) {
				text = text.substring(firstNewline + 1);
			}
			int fence = text.lastIndexOf("```");
			if (fence >= 0) {
				text = text.substring(0, fence);
			}
			text = text.trim();
		}
		// Temizlikten sonra boşalırsa null
		return text.isBlank() ? null : text;
	}

	/**
	 * Model çıktısını parse'a hazır JSON'a sadeleştirir.
	 * Modeller bazen JSON'u ```json ... ``` bloğuna sarar veya ön/son metin ekler;
	 * ilk '{' ile son '}' arasını alarak temizler. Çözümlenemezse ham metni döndürür.
	 */
	private String cleanJson(String raw) {
		// Boş yanıt -> null
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String text = raw.trim();
		// Kod bloğu işaretlerini kaldır
		if (text.startsWith("```")) {
			// ```json veya ``` ile başlıyorsa ilk satır sonuna kadar at
			int firstNewline = text.indexOf('\n');
			if (firstNewline >= 0) {
				text = text.substring(firstNewline + 1);
			}
			// Kapanış ``` varsa kes
			int fence = text.lastIndexOf("```");
			if (fence >= 0) {
				text = text.substring(0, fence);
			}
			text = text.trim();
		}
		// İlk '{' ve son '}' arasını al (ön/son açıklama metnini at)
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return text.substring(start, end + 1).trim();
		}
		// JSON sınırı bulunamadı -> ham (temizlenmiş) metni döndür
		return text;
	}

	// null/blank kontrolü
	private boolean hasText(String v) {
		return v != null && !v.isBlank();
	}
}
