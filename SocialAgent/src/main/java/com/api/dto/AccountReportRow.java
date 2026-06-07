package com.api.dto;

/**
 * Rapor üretimi için bir hesabın özetlenmiş istatistikleri (WorkerPrompt — hesap bazlı aggregate).
 * social_post + post_analysis native join'inden SQL aggregate + Java-side JSON parse ile üretilir.
 * Bu DTO sayesinde OpenAI'ya 150 ham post yerine hesap başına tek bir özet satır gönderilir.
 *
 * @param source               kaynak etiketi: KENDİ HESABIN | RAKİP | SEKTÖR
 * @param accountName          hesap kullanıcı adı
 * @param postCount            analiz edilen toplam gönderi sayısı
 * @param avgLikes             ortalama beğeni sayısı
 * @param avgComments          ortalama yorum sayısı
 * @param avgViews             ortalama görüntülenme sayısı
 * @param imageCount           fotoğraf (IMAGE) tipi gönderi sayısı
 * @param videoCount           video (VIDEO) tipi gönderi sayısı
 * @param carouselCount        carousel tipi gönderi sayısı
 * @param reelCount            reel olarak tespit edilen gönderi sayısı (isReel=true)
 * @param humanCount           görselde insan tespit edilen gönderi sayısı (hasHuman=true)
 * @param modelCount           görselde manken/model tespit edilen gönderi sayısı (hasModel=true)
 * @param productFocusedCount  ürün odaklı tespit edilen gönderi sayısı (isProductFocused=true)
 */
public record AccountReportRow(
		String source,
		String accountName,
		long postCount,
		long avgLikes,
		long avgComments,
		long avgViews,
		long imageCount,
		long videoCount,
		long carouselCount,
		int reelCount,
		int humanCount,
		int modelCount,
		int productFocusedCount) {
}
