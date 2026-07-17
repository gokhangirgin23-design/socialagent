package com.api.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.common.ApiException;
import com.api.common.DataResponse;
import com.api.common.ResponseCode;
import com.api.dto.AddOwnAccountRequest;
import com.api.dto.UserSocialAccountDto;
import com.api.dto.repository.UserSocialAccountRepository;
import com.api.entity.Platform;
import com.api.entity.UserSocialAccount;
import com.api.mapper.UserSocialAccountMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kullanıcının kendi hesabı iş mantığı (concrete; interface yok — CLAUDE.md Madde 1).
 * Unique kısıtlar insert öncesi elle native sorgu ile kontrol edilir (CLAUDE.md Madde 5).
 * Join'ler JdbcTemplate RowMapper ile yapılır (CLAUDE.md Madde 6).
 * Türkçe yorum çoğu satırda (CLAUDE.md Madde 7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

	// Native sorgular için JdbcTemplate
	private final JdbcTemplate jdbcTemplate;

	// JPA repository (yalnızca save/findById kullanılır)
	private final UserSocialAccountRepository userSocialAccountRepository;

	// UserSocialAccount entity -> DTO dönüştürücü
	private final UserSocialAccountMapper userSocialAccountMapper;

	// Hesap adı değişince/silinince Brand DNA cache'ini pasife alır (eski hesaba göre üretim yapılmasın diye)
	private final AccountDnaCacheService accountDnaCacheService;

	/**
	 * Kullanıcının kendi sosyal medya hesabını ekler veya günceller.
	 * D2: tek hesap kuralı.
	 * - Aynı platform+hesap_adı zaten aktifse → DUPLICATE (zaten kayıtlı)
	 * - Farklı hesap adıyla değiştirme: mevcut aktif kaydın adı güncellenir (INSERT yok)
	 * - Hiç kayıt yoksa → INSERT
	 * Endpoint: POST /account/own/add
	 */
	@Transactional
	public UserSocialAccountDto addOwnAccount(UUID userId, AddOwnAccountRequest req) {
		LocalDateTime now = LocalDateTime.now();
		// Locale.ROOT şart: Türkçe JVM locale'inde "instagram".toUpperCase() "İNSTAGRAM" (noktalı İ)
		// üretir ve Platform.INSTAGRAM.name() / DB'deki ASCII "INSTAGRAM" ile hiç eşleşmez.
		String platformNorm = req.getPlatform().toUpperCase(Locale.ROOT);
		String newName = req.getAccountName();
		String newUrl = buildProfileUrl(platformNorm, newName);

		// 1) Aynı kullanıcı + platform + hesap_adı zaten aktif mi? → DUPLICATE
		String sameNameSql = """
				SELECT user_social_account_id
				FROM user_social_account
				WHERE user_id = ? AND platform = ? AND account_name = ? AND active = 1
				""";
		List<UUID> sameNameRows = jdbcTemplate.query(sameNameSql,
				(rs, rowNum) -> rs.getObject("user_social_account_id", UUID.class),
				userId, platformNorm, newName);
		if (!sameNameRows.isEmpty()) {
			throw new ApiException(ResponseCode.DUPLICATE,
					"Bu hesap adı zaten aktif olarak eklenmiş: " + platformNorm + " / " + newName);
		}

		// 2) Kullanıcının bu platform için farklı aktif kaydı var mı? → güncelle (değiştirme)
		// ORDER BY updated_date DESC: D2 (tek hesap) kuralı ihlal edilip (yarış durumu/eski veri
		// nedeniyle) birden fazla aktif satır kalmışsa, en güncel olanı deterministik seçilir.
		String existingActiveSql = """
				SELECT user_social_account_id, user_id, platform, account_name, profile_url,
				       active, created_date, updated_date
				FROM user_social_account
				WHERE user_id = ? AND platform = ? AND active = 1
				ORDER BY updated_date DESC
				LIMIT 1
				""";
		List<UserSocialAccount> activeRows = jdbcTemplate.query(existingActiveSql, (rs, rowNum) -> {
			UserSocialAccount a = new UserSocialAccount();
			a.setUserSocialAccountId(rs.getObject("user_social_account_id", UUID.class));
			a.setUserId(rs.getObject("user_id", UUID.class));
			a.setPlatform(rs.getString("platform"));
			a.setAccountName(rs.getString("account_name"));
			a.setProfileUrl(rs.getString("profile_url"));
			a.setActive(rs.getObject("active", Integer.class));
			if (rs.getTimestamp("created_date") != null)
				a.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
			if (rs.getTimestamp("updated_date") != null)
				a.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
			return a;
		}, userId, platformNorm);

		if (!activeRows.isEmpty()) {
			// Mevcut kaydı yeni hesap adıyla güncelle (D2: tek kayıt korunur)
			UserSocialAccount existing = activeRows.get(0);
			// Hesap adı gerçekten değiştiyse eski hesaba göre üretilmiş Brand DNA cache'i geçersizdir
			// (social_account_id aynı UUID kaldığı için cache key değişmez — elle invalidate şart).
			boolean nameChanged = !newName.equals(existing.getAccountName());
			existing.setAccountName(newName);
			existing.setProfileUrl(newUrl);
			existing.setUpdatedDate(now);
			UserSocialAccount saved = userSocialAccountRepository.save(existing);

			// D2 kendi-onarım: bu kullanıcı+platform için "existing" dışında başka aktif satır
			// kalmışsa (yarış durumu veya bu fix'ten önceki eski veri) burada temizlenir — aksi
			// halde içerik üretimi/resolveOwnSocialAccountId hâlâ eski (yanlış) hesabı seçebilir.
			int cleaned = jdbcTemplate.update("""
					UPDATE user_social_account
					SET active = 0, updated_date = ?
					WHERE user_id = ? AND platform = ? AND active = 1 AND user_social_account_id <> ?
					""", now, userId, platformNorm, existing.getUserSocialAccountId());
			if (cleaned > 0) {
				log.warn("D2 (tek hesap) ihlali onarıldı: userId={}, platform={}, pasife alınan fazla aktif satır={}",
						userId, platformNorm, cleaned);
			}

			if (nameChanged) {
				accountDnaCacheService.invalidateAccountDnaCache(userId);
			}
			return userSocialAccountMapper.toDto(saved);
		}

		// 3) Hiç aktif kayıt yoksa yeni oluştur
		UserSocialAccount account = new UserSocialAccount();
		account.setUserSocialAccountId(UUID.randomUUID());
		account.setUserId(userId);
		account.setPlatform(platformNorm);
		account.setAccountName(newName);
		account.setProfileUrl(newUrl);
		account.setActive(1);
		account.setCreatedDate(now);
		account.setUpdatedDate(now);
		UserSocialAccount saved = userSocialAccountRepository.save(account);
		return userSocialAccountMapper.toDto(saved);
	}

	/**
	 * Kullanıcının aktif kendi hesabını kaldırır (soft delete).
	 * Kayıt bulunamazsa NOT_FOUND döner.
	 * Tek satır bulup güncellemek yerine toplu UPDATE kullanılır: D2 (tek hesap) kuralı ihlal
	 * edilip (yarış durumu/eski veri nedeniyle) birden fazla aktif satır kalmış olsa bile HEPSİ
	 * pasife alınır — aksi halde arta kalan bir "hayalet" aktif satır, kaldırma sonrası bile
	 * içerik üretiminde yanlış hesabın seçilmesine yol açabilir.
	 * Endpoint: POST /account/own/remove
	 */
	@Transactional
	public DataResponse<Void> removeOwnAccount(UUID userId) {
		int updated = jdbcTemplate.update("""
				UPDATE user_social_account
				SET active = 0, updated_date = ?
				WHERE user_id = ? AND active = 1
				""", LocalDateTime.now(), userId);

		if (updated == 0) {
			throw new ApiException(ResponseCode.NOT_FOUND, "Silinecek aktif kendi hesabı bulunamadı");
		}

		// Hesap kaldırıldığında eski Brand DNA cache'i de pasife al
		accountDnaCacheService.invalidateAccountDnaCache(userId);
		return DataResponse.of(ResponseCode.SUCCESS);
	}

	/**
	 * Kullanıcının aktif kendi sosyal hesabını getirir.
	 * Endpoint: POST /account/own/get
	 */
	@Transactional(readOnly = true)
	public UserSocialAccountDto getOwnAccount(UUID userId) {
		// Kullanıcının aktif hesabını native sorgu ile çek (D2: tek hesap)
		// ORDER BY updated_date DESC: birden fazla aktif satır kalmışsa en güncel olan seçilir.
		String sql = """
				SELECT user_social_account_id, user_id, platform, account_name, profile_url,
				       active, created_date, updated_date
				FROM user_social_account
				WHERE user_id = ? AND active = 1
				ORDER BY updated_date DESC
				LIMIT 1
				""";
		List<UserSocialAccount> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
			UserSocialAccount a = new UserSocialAccount();
			a.setUserSocialAccountId(rs.getObject("user_social_account_id", UUID.class));
			a.setUserId(rs.getObject("user_id", UUID.class));
			a.setPlatform(rs.getString("platform"));
			a.setAccountName(rs.getString("account_name"));
			a.setProfileUrl(rs.getString("profile_url"));
			a.setActive(rs.getObject("active", Integer.class));
			if (rs.getTimestamp("created_date") != null) {
				a.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
			}
			if (rs.getTimestamp("updated_date") != null) {
				a.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
			}
			return a;
		}, userId);

		// Hesap bulunamazsa NOT_FOUND (HTTP 200, responseCode=5)
		if (rows.isEmpty()) {
			throw new ApiException(ResponseCode.NOT_FOUND, "Kayıtlı kendi hesabı bulunamadı");
		}

		// MapStruct ile DTO'ya çevir
		return userSocialAccountMapper.toDto(rows.get(0));
	}

	/**
	 * Platforma göre profil URL'i üretir. Şimdilik yalnız INSTAGRAM desteklenir;
	 * ileride farklı platformlar eklenince buraya logic eklenir.
	 *
	 * @return INSTAGRAM için "https://www.instagram.com/{accountName}/"; bilinmeyen platformda null
	 */
	private String buildProfileUrl(String platform, String accountName) {
		if (platform == null || accountName == null) {
			return null;
		}
		// INSTAGRAM: kanonik profil URL'i
		if (Platform.INSTAGRAM.name().equalsIgnoreCase(platform)) {
			return "https://www.instagram.com/" + accountName + "/";
		}
		// Diğer platformlar henüz tanımlı değil
		return null;
	}
}
