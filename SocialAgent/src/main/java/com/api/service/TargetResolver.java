package com.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyProfile;
import com.api.config.AppProperties;
import com.api.entity.AnalysisMode;
import com.api.entity.Platform;
import com.api.entity.UserJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bir job için analysis_mode'a göre çekilecek hesap kümesini belirler (FAZ 5 — CLAUDE.md Bölüm 10).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Component.
 *
 * | Mode             | Hesaplar                                  |
 * | NONE             | sektör top 5                               |
 * | OWN_ONLY         | sektör top 5 + kendi (tek) hesap           |
 * | COMPETITOR_ONLY  | yalnızca rakip (monitored) hesaplar        |
 * | BOTH             | kendi + rakip hesaplar                     |
 *
 * Sektör top 5 (D1): kullanıcının alt-sektör (yoksa sektör) adı keyword olarak Apify'a verilir,
 * follower/engagement'a göre sıralanıp ilk 5 alınır. COMPETITOR_ONLY/BOTH modlarında Apify
 * profil araması ÇAĞRILMAZ (gereksiz maliyet önlenir).
 *
 * Lookup'lar JdbcTemplate native; ilişkili tablolar eski stil "=" join (CLAUDE.md Madde 6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetResolver {

	// Native sorgular için JdbcTemplate
	private final JdbcTemplate jdbcTemplate;

	// Sektör top-5 keyword araması için Apify istemcisi
	private final ApifyClient apifyClient;

	// Sektör top-N limiti gibi ayarlar için (tek kaynak: app.apify.*)
	private final AppProperties appProperties;

	/**
	 * Job'ın moduna göre hedef hesap listesini üretir.
	 *
	 * @param job işlenecek job (analysisMode, userId, selectedUserSocialAccountId, analysisPeriodDays içerir)
	 * @return çekilecek hedefler (boş olabilir)
	 */
	public List<ScrapeTarget> resolve(UserJob job) {
		// Mod string -> enum (geçersizse NONE gibi davran, gürültüsüz)
		AnalysisMode mode = parseMode(job.getAnalysisMode());
		List<ScrapeTarget> targets = new ArrayList<>();

		switch (mode) {
			case NONE -> {
				// Yalnızca sektör top 5
				targets.addAll(resolveSectorTop5(job.getUserId()));
			}
			case OWN_ONLY -> {
				// Sektör top 5 + kendi hesap
				targets.addAll(resolveSectorTop5(job.getUserId()));
				addIfPresent(targets, resolveOwn(job.getSelectedUserSocialAccountId()));
			}
			case COMPETITOR_ONLY -> {
				// Yalnızca rakip hesaplar (Apify profil araması yok)
				targets.addAll(resolveMonitored(job.getUserId()));
			}
			case BOTH -> {
				// Kendi + rakip (Apify profil araması yok)
				addIfPresent(targets, resolveOwn(job.getSelectedUserSocialAccountId()));
				targets.addAll(resolveMonitored(job.getUserId()));
			}
		}

		log.info("Hedefler çözüldü: jobId={}, mode={}, hedefSayısı={}",
				job.getUserJobId(), mode, targets.size());
		return targets;
	}

	// ============================================================
	// Mod bileşenleri
	// ============================================================

	/**
	 * Sektör top 5 (D1): alt-sektör (yoksa sektör) adı keyword -> Apify keyword araması -> ilk N.
	 */
	private List<ScrapeTarget> resolveSectorTop5(UUID userId) {
		// 1) Kullanıcının sektör/alt-sektör id'lerini al (tek tablo lookup)
		String refSql = """
				SELECT sector_id, subsector_id
				FROM user_info
				WHERE user_id = ? AND active = 1
				""";
		List<SectorRef> refs = jdbcTemplate.query(refSql, (rs, rowNum) -> new SectorRef(
				rs.getObject("sector_id", UUID.class),
				rs.getObject("subsector_id", UUID.class)), userId);

		// Kullanıcı/sektör yoksa boş dön
		if (refs.isEmpty() || refs.get(0).sectorId() == null) {
			log.warn("Sektör top-5 için sektör bulunamadı: userId={}", userId);
			return List.of();
		}
		SectorRef ref = refs.get(0);

		// 2) Keyword belirle: alt-sektör adı öncelikli, yoksa sektör adı
		String keyword = null;
		if (ref.subsectorId() != null) {
			keyword = lookupName("subsector", "subsector_id", ref.subsectorId());
		}
		if (keyword == null) {
			keyword = lookupName("sector", "sector_id", ref.sectorId());
		}
		// Keyword hâlâ yoksa boş dön
		if (keyword == null || keyword.isBlank()) {
			log.warn("Sektör top-5 için keyword belirlenemedi: userId={}", userId);
			return List.of();
		}

		// 3) Apify keyword araması -> top N profil (limit config'ten — D1 varsayılan 5)
		int limit = appProperties.getApify().getTopProfilesLimit();
		List<ApifyProfile> profiles = apifyClient.searchTopProfiles(keyword, limit);

		// 4) Profilleri SECTOR hedefine çevir (platform: Instagram)
		List<ScrapeTarget> targets = new ArrayList<>();
		for (ApifyProfile p : profiles) {
			targets.add(ScrapeTarget.sector(Platform.INSTAGRAM.name(), p.accountName()));
		}
		return targets;
	}

	/**
	 * Kendi (tek) hesabı OWN hedefine çevirir; hesap yoksa null.
	 */
	private ScrapeTarget resolveOwn(UUID selectedUserSocialAccountId) {
		// Seçili hesap yoksa OWN hedefi üretilemez
		if (selectedUserSocialAccountId == null) {
			return null;
		}
		// Hesabın platform + account_name'ini çek
		String sql = """
				SELECT platform, account_name
				FROM user_social_account
				WHERE user_social_account_id = ? AND active = 1
				""";
		List<ScrapeTarget> rows = jdbcTemplate.query(sql,
				(rs, rowNum) -> ScrapeTarget.own(
						rs.getString("platform"),
						rs.getString("account_name"),
						selectedUserSocialAccountId),
				selectedUserSocialAccountId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * Kullanıcının izlediği rakip hesapları MONITORED hedeflerine çevirir.
	 * user_monitored_account ve monitored_account eski stil "=" join (CLAUDE.md Madde 6).
	 */
	private List<ScrapeTarget> resolveMonitored(UUID userId) {
		String sql = """
				SELECT ma.monitored_account_id, ma.platform, ma.account_name
				FROM user_monitored_account uma, monitored_account ma
				WHERE uma.user_id = ?
				  AND uma.monitored_account_id = ma.monitored_account_id
				  AND uma.active = 1
				  AND ma.active = 1
				ORDER BY ma.account_name
				""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> ScrapeTarget.monitored(
				rs.getString("platform"),
				rs.getString("account_name"),
				rs.getObject("monitored_account_id", UUID.class)), userId);
	}

	// ============================================================
	// Yardımcılar
	// ============================================================

	/**
	 * Tek tablodan ad (name) çeker; bulunamazsa null. (sector / subsector için ortak.)
	 */
	private String lookupName(String table, String idColumn, UUID id) {
		// Tablo/kolon adları sabit literallerden geldiği için SQL injection riski yok
		String sql = "SELECT name FROM " + table + " WHERE " + idColumn + " = ? AND active = 1";
		List<String> names = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("name"), id);
		return names.isEmpty() ? null : names.get(0);
	}

	/**
	 * Listeye null olmayan hedefi ekler.
	 */
	private void addIfPresent(List<ScrapeTarget> targets, ScrapeTarget target) {
		if (target != null) {
			targets.add(target);
		}
	}

	/**
	 * Mod string'ini enum'a çevirir; geçersizse NONE.
	 */
	private AnalysisMode parseMode(String value) {
		try {
			return AnalysisMode.valueOf(value);
		} catch (Exception e) {
			log.warn("Geçersiz analysisMode='{}', NONE varsayıldı.", value);
			return AnalysisMode.NONE;
		}
	}

	/**
	 * user_info'dan çekilen sektör/alt-sektör id taşıyıcısı (iç kullanım; test erişimi için paket-özel).
	 */
	record SectorRef(UUID sectorId, UUID subsectorId) {
	}
}
