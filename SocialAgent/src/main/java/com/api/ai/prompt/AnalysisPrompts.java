package com.api.ai.prompt;

import com.api.entity.SocialPost;

/**
 * AI analiz prompt template'leri (FAZ 6 — CLAUDE.md Bölüm 11).
 * Hem TEXT (OpenAI) hem medya (Gemini Vision) yolu aynı JSON şemasını üretsin diye
 * ortak bir "JSON sözleşmesi" tanımlanır. Böylece FAZ 7 raporu tek formatla çalışır.
 *
 * Modelden DAİMA salt JSON istenir (markdown/```/açıklama yok) — parse güvenliği için.
 * Analiz boyutları: engagement (etkileşim), tema, ton, hashtag etkinliği, öneri.
 */
public final class AnalysisPrompts {

	// Yardımcı sınıf; örneklenmez
	private AnalysisPrompts() {
	}

	/**
	 * Tüm analizlerin uyacağı ortak JSON şeması (model çıktısı bununla aynı yapıda olmalı).
	 * Anahtarlar İngilizce (stabil); değerler Türkçe içerik üretir.
	 */
	private static final String JSON_SCHEMA = """
			{
			  "engagement": {
			    "level": "LOW | MEDIUM | HIGH",
			    "summary": "etkileşimin (beğeni/yorum/görüntülenme) kısa Türkçe yorumu"
			  },
			  "themes": ["gönderinin ana temaları (Türkçe, 1-5 madde)"],
			  "tone": "gönderinin tonu (ör. samimi, kurumsal, esprili) — Türkçe",
			  "hashtagAnalysis": {
			    "effective": ["işe yarayan hashtag'ler"],
			    "suggestions": ["önerilen ek hashtag'ler"]
			  },
			  "contentSuggestions": ["içeriği iyileştirmek için Türkçe öneriler (1-3 madde)"]
			}
			""";

	// Modelin rolünü ve katı çıktı kuralını veren ortak yönerge
	private static final String SYSTEM_RULE = """
			Sen bir sosyal medya içerik analistisin. Sana verilen Instagram gönderisini analiz et.
			SADECE aşağıdaki şemaya birebir uyan geçerli bir JSON döndür.
			Markdown, kod bloğu (```), açıklama veya ön/son metin EKLEME. Yalnızca JSON.
			Tüm metin değerlerini TÜRKÇE yaz.
			Şema:
			%s
			""".formatted(JSON_SCHEMA);

	/**
	 * TEXT/caption analizi için OpenAI prompt'u (metin yolu — D3).
	 * Caption + hashtag + etkileşim metrikleri verilir; model JSON döndürür.
	 */
	public static String forText(SocialPost post) {
		// Etkileşim metriklerini güvenli (null -> 0) biçimde aktar
		long likes = nz(post.getLikesCount());
		long comments = nz(post.getCommentsCount());
		long views = nz(post.getViewsCount());

		return """
				%s

				Analiz edilecek gönderi:
				- Platform: %s
				- Medya türü: %s
				- Caption: %s
				- Hashtag'ler: %s
				- Beğeni: %d, Yorum: %d, Görüntülenme: %d

				Şimdi şemaya uygun JSON üret.
				""".formatted(
				SYSTEM_RULE,
				safe(post.getPlatform()),
				safe(post.getMediaType()),
				safe(post.getCaption()),
				safe(post.getHashtags()),
				likes, comments, views);
	}

	/**
	 * IMAGE/VIDEO/CAROUSEL analizi için Gemini Vision prompt'unun METİN kısmı (görsel yolu — D3).
	 * Görsel ayrıca ImageContent olarak modele iletilir (AiAnalysisService).
	 */
	public static String forMedia(SocialPost post) {
		long likes = nz(post.getLikesCount());
		long comments = nz(post.getCommentsCount());
		long views = nz(post.getViewsCount());

		return """
				%s

				Sana bu gönderinin görseli de veriliyor. Hem görseli hem aşağıdaki metni değerlendir.
				Gönderi bilgileri:
				- Platform: %s
				- Medya türü: %s
				- Caption: %s
				- Hashtag'ler: %s
				- Beğeni: %d, Yorum: %d, Görüntülenme: %d

				Görselin içeriğini (konu, kompozisyon, marka öğeleri) de dikkate alarak,
				şemaya uygun JSON üret.
				""".formatted(
				SYSTEM_RULE,
				safe(post.getPlatform()),
				safe(post.getMediaType()),
				safe(post.getCaption()),
				safe(post.getHashtags()),
				likes, comments, views);
	}

	// null Long -> 0 (prompt'ta güvenli sayı)
	private static long nz(Long v) {
		return v != null ? v : 0L;
	}

	// null/blank metin -> "-" (prompt okunaklı kalsın)
	private static String safe(String v) {
		return (v == null || v.isBlank()) ? "-" : v;
	}
}
