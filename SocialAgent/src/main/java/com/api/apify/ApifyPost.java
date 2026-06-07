package com.api.apify;

import java.time.LocalDateTime;

/**
 * Apify instagram-scraper aktöründen dönen tek bir gönderi (CLAUDE.md Bölüm 10).
 * Worker bu kaydı social_post tablosuna yazar (UNIQUE(platform, platformPostId) servis kontrolü).
 * mediaType ham değer değil, MediaType.fromRaw ile normalize edilmiş kanonik değerdir
 * (IMAGE | VIDEO | CAROUSEL | TEXT).
 *
 * @param platformPostId  platforma özgü gönderi kimliği (Instagram shortCode/id) — dedup anahtarı
 * @param ownerUsername   gönderiyi paylaşan hesap kullanıcı adı (SECTOR postlarında sector_account_name)
 * @param postUrl         gönderi URL'i
 * @param caption         gönderi metni
 * @param hashtags        "#a #b" formatında birleştirilmiş hashtag'ler
 * @param mediaUrl        medya (görsel/video kapağı) URL'i
 * @param mediaType       normalize medya türü (MediaType.name())
 * @param likesCount      beğeni sayısı (yoksa null)
 * @param commentsCount   yorum sayısı (yoksa null)
 * @param viewsCount      görüntülenme sayısı (yoksa null)
 * @param sharesCount     paylaşım sayısı (yoksa null)
 * @param postDate        yayınlanma tarihi (yoksa null)
 * @param rawJson         Apify'dan gelen ham JSON metni — social_post.result_json kolonuna yazılır
 */
public record ApifyPost(
		String platformPostId,
		String ownerUsername,
		String postUrl,
		String caption,
		String hashtags,
		String mediaUrl,
		String mediaType,
		Long likesCount,
		Long commentsCount,
		Long viewsCount,
		Long sharesCount,
		LocalDateTime postDate,
		String rawJson) {
}
