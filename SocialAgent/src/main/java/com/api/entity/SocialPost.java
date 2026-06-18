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
 * Kaynak hesap kimliği source_type kolonuna göre temsil edilir:
 *  - OWN (kendi hesabı): source_type=OWN; sector_account_name + monitored_account_id null.
 *  - MONITORED (rakip): source_type=MONITORED; monitoredAccountId dolu.
 *  - SECTOR (sektör araştırması): source_type=SECTOR; sectorAccountName dolu (Apify ownerUsername).
 *
 * UNIQUE(platform, platformPostId) — mevcut gönderi varsa save-or-update yapılır (servis katmanı).
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

	// İsteği oluşturan rapor isteğinin id'si
	@Column(name = "request_id")
	private UUID requestId;

	// Rakip hesap id'si (yalnızca MONITORED kaynağında dolu; diğerlerinde null)
	@Column(name = "monitored_account_id")
	private UUID monitoredAccountId;

	// Gönderinin kaynağı: OWN | MONITORED | SECTOR (kaynak ayrımı bu kolondan yapılır)
	@Column(name = "source_type")
	private String sourceType;

	// Sektör hesabının adı (yalnızca SECTOR kaynağında dolu; Apify ownerUsername)
	@Column(name = "sector_account_name")
	private String sectorAccountName;

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

	// Apify'dan dönen ham JSON (result_json) — OpenAI analiz promptuna ham veri olarak verilir
	@Column(name = "result_json", columnDefinition = "TEXT")
	private String resultJson;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
