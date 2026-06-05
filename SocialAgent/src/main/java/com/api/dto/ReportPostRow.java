package com.api.dto;

/**
 * Rapor üretimi için bir analiz satırının projeksiyonu (FAZ 7).
 * post_analysis ⋈ social_post native join'inden (eski stil "=") üretilir; ReportPrompts'a
 * verilir. Entity değildir; yalnızca rapor prompt'unu beslemek için taşıyıcı bir kayıttır.
 *
 * @param source        kaynak etiketi (KENDİ HESABIN | RAKİP | SEKTÖR(ad)) — karşılaştırmalı rapor için
 * @param mediaType     gönderinin medya türü (IMAGE/VIDEO/CAROUSEL/TEXT)
 * @param caption       gönderi metni (kısaltılabilir)
 * @param hashtags      hashtag'ler
 * @param likesCount    beğeni sayısı (null olabilir)
 * @param commentsCount yorum sayısı (null olabilir)
 * @param viewsCount    görüntülenme sayısı (null olabilir)
 * @param analysisJson  FAZ 6'da üretilen post bazlı analiz JSON'ı (rapor girdisi)
 */
public record ReportPostRow(
		String source,
		String mediaType,
		String caption,
		String hashtags,
		Long likesCount,
		Long commentsCount,
		Long viewsCount,
		String analysisJson) {
}
