package com.api.entity;

/**
 * Sosyal medya gönderisinin medya türü (CLAUDE.md Bölüm 6).
 * AI yönlendirmesinde kullanılır (FAZ 6): TEXT -> OpenAI, IMAGE/VIDEO/CAROUSEL -> Gemini Vision.
 */
public enum MediaType {

	// Düz görsel gönderi
	IMAGE,

	// Video gönderi (reel dahil)
	VIDEO,

	// Çoklu medya (Instagram "sidecar"/galeri)
	CAROUSEL,

	// Yalnızca metin/caption (medya yok)
	TEXT;

	/**
	 * Apify'dan gelen ham tür değerini (büyük/küçük harf, farklı adlandırma) MediaType'a çevirir.
	 * Eşleşme bulunamazsa güvenli varsayılan olarak TEXT döner.
	 *
	 * @param raw Apify post tipi (ör. "Image", "Video", "Sidecar", "GraphImage")
	 * @return normalize edilmiş MediaType
	 */
	public static MediaType fromRaw(String raw) {
		// Boş/null gelirse metin kabul et
		if (raw == null || raw.isBlank()) {
			return TEXT;
		}
		// Karşılaştırmayı büyük harfe normalize et
		String v = raw.trim().toUpperCase();
		// Instagram galeri tipleri -> CAROUSEL
		if (v.contains("SIDECAR") || v.contains("CAROUSEL") || v.contains("GALLERY")) {
			return CAROUSEL;
		}
		// Video/reel tipleri -> VIDEO
		if (v.contains("VIDEO") || v.contains("REEL") || v.contains("CLIP")) {
			return VIDEO;
		}
		// Görsel tipleri -> IMAGE
		if (v.contains("IMAGE") || v.contains("PHOTO") || v.contains("GRAPHIMAGE")) {
			return IMAGE;
		}
		// Tanınmayan tür -> TEXT (caption üzerinden analiz edilir)
		return TEXT;
	}
}
