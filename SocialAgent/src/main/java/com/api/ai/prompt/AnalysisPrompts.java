package com.api.ai.prompt;

import com.api.entity.SocialPost;

/**
 * AI analiz prompt template'leri (WorkerPrompt revizyonu — CLAUDE.md Bölüm 11).
 *
 * Her post için iki ayrı model çalışır:
 *   1) OpenAI  → result_json (ham Apify metrik JSON'u) → metrik/etkileşim analizi
 *   2) Gemini  → media_url (görsel URL) → görsel içerik analizi
 *
 * Modelden DAİMA salt JSON istenir (markdown/```/açıklama yok) — parse güvenliği için.
 * Çıktılar AiAnalysisService tarafından birleştirilip post_analysis.analysis_json'a yazılır.
 */
public final class AnalysisPrompts {

	private AnalysisPrompts() {
	}

	// ============================================================
	// OpenAI — result_json (metrik/etkileşim analizi) şeması
	// ============================================================

	private static final String OPENAI_SCHEMA = """
			{
			  "engagement": {
			    "level": "LOW | MEDIUM | HIGH",
			    "avgLikes": <beğeni sayısı veya null>,
			    "avgComments": <yorum sayısı veya null>,
			    "avgViews": <görüntülenme sayısı veya null>,
			    "engagementRate": "<hesaplanabiliyorsa yüzde string, yoksa null>",
			    "summary": "etkileşim kısa Türkçe yorumu"
			  },
			  "contentType": {
			    "mediaType": "IMAGE | VIDEO | CAROUSEL | TEXT | REEL",
			    "isReel": <true/false — URL'de /reel/ varsa veya type=clips ise true>
			  },
			  "themes": ["gönderinin ana temaları (Türkçe, 1-5 madde)"],
			  "tone": "gönderinin tonu (ör. samimi, kurumsal, esprili) — Türkçe",
			  "captionAnalysis": {
			    "summary": "açıklamanın (caption) kısa yorumu (Türkçe)",
			    "callToAction": "varsa harekete geçirici mesaj (CTA) — yoksa null",
			    "sentiment": "pozitif | nötr | negatif",
			    "keyPhrases": ["göze çarpan anahtar kelimeler veya cümleler (Türkçe, 1-3 madde)"]
			  },
			  "hashtagAnalysis": {
			    "effective": ["işe yarayan hashtag'ler"],
			    "suggestions": ["önerilen ek hashtag'ler"]
			  },
			  "ownerFollowersCount": <ownerFollowersCount değeri varsa integer, yoksa null>
			}
			""";

	private static final String OPENAI_SYSTEM_RULE = """
			Sen bir sosyal medya veri analistisin. Sana bir Instagram gönderisinin ham Apify JSON verisi veriliyor.
			Bu veriyi analiz et. SADECE aşağıdaki şemaya birebir uyan geçerli bir JSON döndür.
			Markdown, kod bloğu (```), açıklama veya ön/son metin EKLEME. Yalnızca JSON.
			Tüm metin değerlerini TÜRKÇE yaz.
			Şema:
			%s
			""".formatted(OPENAI_SCHEMA);

	// ============================================================
	// Gemini Vision — media_url (görsel içerik analizi) şeması
	// ============================================================

	private static final String GEMINI_SCHEMA = """
			{
			  "hasHuman": <true/false — görselde insan var mı>,
			  "hasModel": <true/false — görselde manken/model var mı>,
			  "isProductFocused": <true/false — içerik ürün odaklı mı>,
			  "visualThemes": ["görselin ana temaları (Türkçe, 1-3 madde)"],
			  "composition": "görsel kompozisyon kısa yorumu (Türkçe)",
			  "brandElements": ["varsa marka öğeleri (logo, renk, font vb.) — yoksa boş dizi"],
			  "sceneDescription": "sahnede ne görünüyor: ortam, ışık, renk paleti, mekan (Türkçe)",
			  "textOverlay": "görselin üzerindeki yazılar veya mesajlar (yoksa null)",
			  "hasMusic": <true/false — video ise müzik/ses var gibi görünüyor mu; görselden anlaşılmıyorsa false>,
			  "musicMood": "müzik/ses varsa tahmini tonu: enerjik, duygusal, sakin, neşeli vb. (yoksa null)",
			  "contentIdea": "bu görsel/video formatından ilham alarak benzer içerik oluşturmak isteyenlere somut fikir önerisi (Türkçe, 1-2 cümle)"
			}
			""";

	private static final String GEMINI_SYSTEM_RULE = """
			Sen bir görsel içerik analistisin. Sana bir Instagram gönderisinin görseli veriliyor.
			Görseli analiz et. SADECE aşağıdaki şemaya birebir uyan geçerli bir JSON döndür.
			Markdown, kod bloğu (```), açıklama veya ön/son metin EKLEME. Yalnızca JSON.
			Tüm metin değerlerini TÜRKÇE yaz.
			Şema:
			%s
			""".formatted(GEMINI_SCHEMA);

	// ============================================================
	// Public metodlar
	// ============================================================

	/**
	 * OpenAI için result_json bazlı metrik analiz promptu.
	 * Ham Apify JSON'u (result_json) direkt prompt'a eklenir.
	 *
	 * @param post analiz edilecek gönderi (result_json + temel alanlar)
	 * @return OpenAI'ya gönderilecek prompt
	 */
	public static String forMetrics(SocialPost post) {
		return """
				%s

				Analiz edilecek Instagram gönderisinin ham Apify JSON verisi:
				%s

				Gönderi URL: %s
				Şimdi şemaya uygun JSON üret.
				""".formatted(
				OPENAI_SYSTEM_RULE,
				safe(post.getResultJson()),
				safe(post.getPostUrl()));
	}

	/**
	 * Gemini Vision için görsel analiz promptunun metin kısmı.
	 * Görsel ayrıca ImageContent olarak modele iletilir (AiAnalysisService).
	 *
	 * @param post analiz edilecek gönderi (media_url AiAnalysisService tarafından ImageContent olarak eklenir)
	 * @return Gemini'ye gönderilecek metin prompt
	 */
	public static String forVisual(SocialPost post) {
		return """
				%s

				Gönderi bilgileri (bağlam için):
				- Caption: %s
				- Medya türü: %s
				- Platform: %s

				Görseli analiz ederek şemaya uygun JSON üret.
				""".formatted(
				GEMINI_SYSTEM_RULE,
				safe(post.getCaption()),
				safe(post.getMediaType()),
				safe(post.getPlatform()));
	}

	// null/blank metin -> "-" (prompt okunaklı kalsın)
	private static String safe(String v) {
		return (v == null || v.isBlank()) ? "-" : v;
	}
}
