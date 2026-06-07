package com.api.dto;

/**
 * Rapor üretimi için bir analiz satırının projeksiyonu (FAZ 7 — WorkerPrompt revizyonu).
 * post_analysis ⋈ social_post native join'inden (eski stil "=") üretilir; ReportPrompts'a verilir.
 *
 * @param source        kaynak etiketi (KENDİ HESABIN | RAKİP | SEKTÖR(ad)) — karşılaştırmalı rapor için
 * @param ownerUsername gönderiyi paylaşan hesap kullanıcı adı (SEKTÖR postlarında account_name_sector)
 * @param mediaType     gönderinin medya türü (IMAGE/VIDEO/CAROUSEL/TEXT)
 * @param caption       gönderi metni (kısaltılabilir)
 * @param hashtags      hashtag'ler
 * @param likesCount    beğeni sayısı (null olabilir)
 * @param commentsCount yorum sayısı (null olabilir)
 * @param viewsCount    görüntülenme sayısı (null olabilir)
 * @param resultJson    ham Apify JSON — rapor promptuna metrik ham veri olarak eklenir
 * @param analysisJson  FAZ 6'da üretilen birleşik analiz JSON'ı (metrics + visual)
 */
public record ReportPostRow(
		String source,
		String ownerUsername,
		String mediaType,
		String caption,
		String hashtags,
		Long likesCount,
		Long commentsCount,
		Long viewsCount,
		String resultJson,
		String analysisJson) {
}
