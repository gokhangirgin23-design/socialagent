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
			  "productCategory": "görüntülenen ürün/yiyecek/hizmet kategorisi — ör: kebap, döner, köfte, pizza, kahve, ayakkabı, elbise, teknoloji (net değilse null)",
			  "specificProduct": "görseldeki somut ürün veya yemek adı — ör: Adana kebap, cheesecake, espresso, kırmızı elbise (belirlenemiyorsa null)",
			  "visualThemes": ["görselin ana temaları (Türkçe, 1-4 madde)"],
			  "shootingStyle": "çekim stili: close-up | flat-lay | overhead | lifestyle | portrait | wide-shot | macro | editorial — sadece bu değerlerden biri",
			  "lightingStyle": "aydınlatma: doğal ışık | stüdyo ışığı | sıcak ışık | soğuk ışık | dramatik | soft | karanlık | arka ışık — sadece bu değerlerden biri",
			  "colorPalette": ["görseldeki baskın 3-5 renk (Türkçe renk adları: kırmızı, turuncu, bej, lacivert, altın sarısı vb.)"],
			  "backgroundType": "arka plan: düz renk | ahşap | mermer | metal | dış mekan | restoran iç mekan | ev ortamı | şehir | doğa | stüdyo | soyut — sadece bu değerlerden biri",
			  "atmosphere": "görselin atmosferi: sıcak | soğuk | lüks | sade | canlı | sakin | rustik | modern | vintage | minimalist — sadece bu değerlerden biri",
			  "composition": "kompozisyon yorumu (Türkçe, kısa: çekim açısı, nesne yerleşimi)",
			  "brandElements": ["varsa marka öğeleri (logo, renk kodu, font, marka adı vb.) — yoksa boş dizi"],
			  "propsAndDecor": ["kullanılan aksesuar/dekor öğeleri — ör: tahta kesme tahtası, çiçek, kumaş, tabak, çatal, mumlar (yoksa boş dizi)"],
			  "contentStyle": "içerik stili: ürün odaklı | yaşam tarzı | mekan tanıtımı | kişisel | soyut | eğitici | sosyal kanıt — sadece bu değerlerden biri",
			  "sceneDescription": "sahnede ne görünüyor: ürün adı, ortam, ışık, baskın renkler, mekan, dikkat çekici detaylar (Türkçe, 2-3 cümle)",
			  "textOverlay": "görsel üzerindeki yazılar veya mesajlar (yoksa null)",
			  "hasMusic": <true/false — video ise müzik/ses var gibi görünüyor mu; görselden anlaşılmıyorsa false>,
			  "musicMood": "müzik/ses varsa tahmini ton: enerjik, duygusal, sakin, neşeli vb. (yoksa null)",
			  "contentIdea": "bu formattan ilham alan, aynı ürün kategorisinde uygulanabilir somut içerik fikri (Türkçe, 1-2 cümle)"
			}
			""";

	private static final String GEMINI_SYSTEM_RULE = """
			Sen uzman bir marka görsel analisti ve sosyal medya içerik stratejistisin.
			Sana bir Instagram gönderisinin görseli veriliyor. Görsel bir yiyecek markasına, ürüne veya hizmete ait olabilir.
			Görseli çok dikkatli analiz et: ürünü, arka planı, ışığı, rengi, atmosferi, aksesuarları ve çekim stilini tespit et.
			SADECE aşağıdaki şemaya birebir uyan geçerli bir JSON döndür.
			Markdown, kod bloğu (```), açıklama veya ön/son metin EKLEME. Yalnızca JSON.
			Tüm metin değerlerini TÜRKÇE yaz.
			productCategory ve specificProduct alanlarına özellikle dikkat et: görseldeki ürünü mümkün olduğunca spesifik tanımla.
			Şema:
			%s
			""".formatted(GEMINI_SCHEMA);

	// ============================================================
	// Public metodlar
	// ============================================================

	/**
	 * OpenAI için result_json bazlı metrik analiz promptu.
	 * Ham Apify JSON'u yerine (maliyet optimizasyonu — AiAnalysisService.trimResultJson)
	 * ÖNCEDEN whitelist ile trim edilmiş JSON prompt'a eklenir.
	 *
	 * @param post              analiz edilecek gönderi (temel alanlar için)
	 * @param trimmedResultJson trim edilmiş Apify JSON verisi (bkz. AiAnalysisService.trimResultJson)
	 * @return OpenAI'ya gönderilecek prompt
	 */
	public static String forMetrics(SocialPost post, String trimmedResultJson) {
		return """
				%s

				Analiz edilecek Instagram gönderisinin ham Apify JSON verisi:
				%s

				Gönderi URL: %s
				Şimdi şemaya uygun JSON üret.
				""".formatted(
				OPENAI_SYSTEM_RULE,
				safe(trimmedResultJson),
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
