package com.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Çekilen sosyal medya gönderisi (social_post tablosu — CLAUDE.md Bölüm 5).
 * FAZ 5'te worker tarafından Apify'dan çekilip kaydedilir; FAZ 6'da AI ile analiz edilir.
 * İlişkiler nesne referansı ile değil, yalnızca ID kolonları ile tutulur (CLAUDE.md Madde 6).
 *
 * Kaynak hesap kimliği üç şekilde temsil edilir (target tipine göre):
 *  - MONITORED (rakip): monitoredAccountId dolu.
 *  - SECTOR (sektör top 5): platformSector + accountNameSector dolu.
 *  - OWN (kendi hesabı): yukarıdakiler null; job.selectedUserSocialAccountId üzerinden bağlanır.
 *
 * UNIQUE(platform, platformPostId) hem şemada hem servis katmanında kontrol edilir (CLAUDE.md Madde 5).
 */
@Entity
@Table(name = "social_post")
@Getter
@Setter
public class SocialPost {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "social_post_id")
	private UUID socialPostId;

	// Gönderiyi çeken job'ın id'si
	@Column(name = "user_job_id")
	private UUID userJobId;

	// Rakip hesap id'si (yalnızca MONITORED target'larda dolu; diğerlerinde null)
	@Column(name = "monitored_account_id")
	private UUID monitoredAccountId;

	// Sektör top-5 hesabının platformu (yalnızca SECTOR target'larda dolu; D1)
	@Column(name = "platform_sector")
	private String platformSector;

	// Sektör top-5 hesabının adı (yalnızca SECTOR target'larda dolu; D1)
	@Column(name = "account_name_sector")
	private String accountNameSector;

	// Gönderinin platformu (örn. "INSTAGRAM")
	@Column(name = "platform")
	private String platform;

	// Platforma özgü gönderi kimliği (Instagram shortCode/id) — dedup anahtarı
	@Column(name = "platform_post_id")
	private String platformPostId;

	// Gönderi URL'i
	@Column(name = "post_url")
	private String postUrl;

	// Gönderi metni / caption (TEXT analizinde kullanılır)
	@Column(name = "caption")
	private String caption;

	// Hashtag'ler (boşlukla ayrılmış "#a #b" formatında saklanır)
	@Column(name = "hashtags")
	private String hashtags;

	// Medya URL'i (görsel/video kapağı)
	@Column(name = "media_url")
	private String mediaUrl;

	// Medya türü: IMAGE | VIDEO | CAROUSEL | TEXT (MediaType enum)
	@Column(name = "media_type")
	private String mediaType;

	// Beğeni sayısı
	@Column(name = "likes_count")
	private Long likesCount;

	// Yorum sayısı
	@Column(name = "comments_count")
	private Long commentsCount;

	// Görüntülenme sayısı (video/reel için)
	@Column(name = "views_count")
	private Long viewsCount;

	// Paylaşım sayısı (çoğu zaman Apify döndürmez -> null olabilir)
	@Column(name = "shares_count")
	private Long sharesCount;

	// Gönderinin yayınlanma tarihi
	@Column(name = "post_date")
	private LocalDateTime postDate;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
