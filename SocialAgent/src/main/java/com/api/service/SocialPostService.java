package com.api.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.apify.ApifyPost;
import com.api.dto.repository.SocialPostRepository;
import com.api.entity.SocialPost;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * social_post yazma + tekrar-analiz koruması iş mantığı (FAZ 5 — CLAUDE.md Bölüm 10).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * - Tekrar-analiz koruması: Apify'dan önce post_analysis JOIN'lenir; son analysisPeriodDays
 *   içinde o hesap analiz edilmişse Apify'dan çekilmez (hedef tipine göre kimlik değişir).
 * - Insert: UNIQUE(platform, platform_post_id) servis katmanında elle kontrol edilir (CLAUDE.md Madde 5).
 *
 * Join'ler eski stil "=" + JdbcTemplate native (CLAUDE.md Madde 6); insert JPA save.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialPostService {

	// Native sorgular için JdbcTemplate
	private final JdbcTemplate jdbcTemplate;

	// social_post insert için JPA repository
	private final SocialPostRepository socialPostRepository;

	/**
	 * Hedef hesap son analysisPeriodDays içinde analiz edildi mi? (tekrar-analiz koruması).
	 * Kimlik hedef tipine göre seçilir:
	 *  - MONITORED: monitored_account_id
	 *  - SECTOR:    platform_sector + account_name_sector
	 *  - OWN:       user_job.selected_user_social_account_id (job zinciri üzerinden)
	 *
	 * @return son pencerede analizi varsa true (Apify atlanır)
	 */
	@Transactional(readOnly = true)
	public boolean isRecentlyAnalyzed(ScrapeTarget target, int analysisPeriodDays) {
		// Pencere alt sınırı: now - analysisPeriodDays
		Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusDays(analysisPeriodDays));

		// Hedef tipine göre native JOIN sorgusu (post_analysis ile)
		List<UUID> rows;
		switch (target.type()) {
			case MONITORED -> {
				// Rakip hesap: monitored_account_id eşleşmesi
				String sql = """
						SELECT sp.social_post_id
						FROM social_post sp, post_analysis pa
						WHERE sp.social_post_id = pa.social_post_id
						  AND sp.monitored_account_id = ?
						  AND pa.created_date >= ?
						""";
				rows = jdbcTemplate.query(sql,
						(rs, rowNum) -> rs.getObject("social_post_id", UUID.class),
						target.monitoredAccountId(), cutoff);
			}
			case SECTOR -> {
				// Sektör top-5 hesabı: platform_sector + account_name_sector eşleşmesi
				String sql = """
						SELECT sp.social_post_id
						FROM social_post sp, post_analysis pa
						WHERE sp.social_post_id = pa.social_post_id
						  AND sp.platform_sector = ?
						  AND sp.account_name_sector = ?
						  AND pa.created_date >= ?
						""";
				rows = jdbcTemplate.query(sql,
						(rs, rowNum) -> rs.getObject("social_post_id", UUID.class),
						target.platform(), target.accountName(), cutoff);
			}
			case OWN -> {
				// Kendi hesabı: job zinciri üzerinden selected_user_social_account_id eşleşmesi
				String sql = """
						SELECT sp.social_post_id
						FROM social_post sp, post_analysis pa, user_job uj
						WHERE sp.social_post_id = pa.social_post_id
						  AND sp.user_job_id = uj.user_job_id
						  AND uj.selected_user_social_account_id = ?
						  AND pa.created_date >= ?
						""";
				rows = jdbcTemplate.query(sql,
						(rs, rowNum) -> rs.getObject("social_post_id", UUID.class),
						target.selectedUserSocialAccountId(), cutoff);
			}
			default -> rows = List.of();
		}

		boolean recent = !rows.isEmpty();
		if (recent) {
			// Pencere içinde analiz var -> Apify atlanacak
			log.info("Tekrar-analiz koruması: hesap son {} günde analiz edilmiş, Apify atlanıyor (tip={}, hesap={}).",
					analysisPeriodDays, target.type(), target.accountName());
		}
		return recent;
	}

	/**
	 * Bir hedefin Apify'dan çekilen gönderilerini social_post'a yazar.
	 * Her gönderi için UNIQUE(platform, platform_post_id) elle kontrol edilir;
	 * zaten varsa atlanır (CLAUDE.md Madde 5).
	 *
	 * @return eklenen (yeni) gönderi sayısı
	 */
	@Transactional
	public int saveRecentPosts(UUID userJobId, ScrapeTarget target, List<ApifyPost> posts) {
		// Boş liste -> iş yok
		if (posts == null || posts.isEmpty()) {
			return 0;
		}
		LocalDateTime now = LocalDateTime.now();
		int inserted = 0;

		for (ApifyPost post : posts) {
			// 1) Dedup: bu platform + platform_post_id zaten var mı?
			String dupSql = """
					SELECT social_post_id
					FROM social_post
					WHERE platform = ? AND platform_post_id = ?
					""";
			List<UUID> existing = jdbcTemplate.query(dupSql,
					(rs, rowNum) -> rs.getObject("social_post_id", UUID.class),
					target.platform(), post.platformPostId());

			// Zaten kayıtlıysa atla (servis seviyesinde unique kontrolü)
			if (!existing.isEmpty()) {
				log.debug("Gönderi zaten kayıtlı, atlanıyor: platform={}, postId={}",
						target.platform(), post.platformPostId());
				continue;
			}

			// 2) Yeni social_post oluştur (hedef tipine göre kaynak kolonları doldur)
			SocialPost sp = new SocialPost();
			sp.setSocialPostId(UUID.randomUUID());
			sp.setUserJobId(userJobId);
			// Kaynak kimlik kolonları (yalnızca ilgili tip için dolu)
			sp.setMonitoredAccountId(target.type() == ScrapeTarget.TargetType.MONITORED
					? target.monitoredAccountId() : null);
			sp.setPlatformSector(target.type() == ScrapeTarget.TargetType.SECTOR
					? target.platform() : null);
			sp.setAccountNameSector(target.type() == ScrapeTarget.TargetType.SECTOR
					? target.accountName() : null);
			// Gönderi alanları
			sp.setPlatform(target.platform());
			sp.setPlatformPostId(post.platformPostId());
			sp.setPostUrl(post.postUrl());
			sp.setCaption(post.caption());
			sp.setHashtags(post.hashtags());
			sp.setMediaUrl(post.mediaUrl());
			sp.setMediaType(post.mediaType());
			sp.setLikesCount(post.likesCount());
			sp.setCommentsCount(post.commentsCount());
			sp.setViewsCount(post.viewsCount());
			sp.setSharesCount(post.sharesCount());
			sp.setPostDate(post.postDate());
			sp.setCreatedDate(now);
			sp.setUpdatedDate(now);

			// 3) JPA save ile insert
			socialPostRepository.save(sp);
			inserted++;
		}

		log.info("social_post yazıldı: jobId={}, tip={}, hesap={}, gelen={}, eklenen={}",
				userJobId, target.type(), target.accountName(), posts.size(), inserted);
		return inserted;
	}
}
