package com.api.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.common.ApiException;
import com.api.common.DataResponse;
import com.api.common.ResponseCode;
import com.api.dto.AddMonitoredAccountRequest;
import com.api.dto.AddOwnAccountRequest;
import com.api.dto.MonitoredAccountDto;
import com.api.dto.UserSocialAccountDto;
import com.api.dto.repository.MonitoredAccountRepository;
import com.api.dto.repository.UserMonitoredAccountRepository;
import com.api.dto.repository.UserSocialAccountRepository;
import com.api.entity.MonitoredAccount;
import com.api.entity.Platform;
import com.api.entity.UserMonitoredAccount;
import com.api.entity.UserSocialAccount;
import com.api.mapper.UserSocialAccountMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kullanıcı kendi hesabı ve izlenen (rakip) hesap iş mantığı (concrete; interface yok — CLAUDE.md Madde 1).
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

	// JPA repository'leri (yalnızca save/findById kullanılır)
	private final UserSocialAccountRepository userSocialAccountRepository;
	private final MonitoredAccountRepository monitoredAccountRepository;
	private final UserMonitoredAccountRepository userMonitoredAccountRepository;

	// UserSocialAccount entity -> DTO dönüştürücü
	private final UserSocialAccountMapper userSocialAccountMapper;
	
	//test

	/**
	 * Kullanıcının kendi sosyal medya hesabını ekler.
	 * Unique(user_id, platform, account_name) kısıtı kontrol edilir.
	 * D2: tek hesap kuralı; zaten aynı platform+account_name varsa DUPLICATE döner.
	 * Endpoint: POST /account/own/add
	 */
	@Transactional
	public UserSocialAccountDto addOwnAccount(UUID userId, AddOwnAccountRequest req) {
		LocalDateTime now = LocalDateTime.now();

		// 1) Unique kısıt kontrolü: aynı kullanıcı + platform + accountName var mı?
		String uniqueCheckSql = """
				SELECT user_social_account_id
				FROM user_social_account
				WHERE user_id = ? AND platform = ? AND account_name = ? AND active = 1
				""";
		List<UUID> existing = jdbcTemplate.query(uniqueCheckSql,
				(rs, rowNum) -> rs.getObject("user_social_account_id", UUID.class),
				userId, req.getPlatform(), req.getAccountName());

		// Kayıt zaten varsa DUPLICATE döndür (HTTP 200, responseCode=3)
		if (!existing.isEmpty()) {
			throw new ApiException(ResponseCode.DUPLICATE,
					"Bu platform ve hesap adı zaten eklenmiş: " + req.getPlatform() + " / " + req.getAccountName());
		}

		// 2) Yeni kayıt oluştur ve JPA ile kaydet
		UserSocialAccount account = new UserSocialAccount();
		account.setUserSocialAccountId(UUID.randomUUID());
		account.setUserId(userId);
		// Platform string olarak saklanır (enum doğrulaması iş katmanında yapılabilir)
		account.setPlatform(req.getPlatform().toUpperCase());
		account.setAccountName(req.getAccountName());
		// profileUrl istekten alınmaz; platforma göre otomatik üretilir (INSTAGRAM şimdilik)
		account.setProfileUrl(buildProfileUrl(req.getPlatform().toUpperCase(), req.getAccountName()));
		account.setActive(1);
		account.setCreatedDate(now);
		account.setUpdatedDate(now);

		// JPA save ile insert
		UserSocialAccount saved = userSocialAccountRepository.save(account);

		// 3) Eklenen kaydı DTO olarak döndür
		return userSocialAccountMapper.toDto(saved);
	}

	/**
	 * Kullanıcının aktif kendi sosyal hesabını getirir.
	 * Endpoint: POST /account/own/get
	 */
	@Transactional(readOnly = true)
	public UserSocialAccountDto getOwnAccount(UUID userId) {
		// Kullanıcının aktif hesabını native sorgu ile çek (D2: tek hesap)
		String sql = """
				SELECT user_social_account_id, user_id, platform, account_name, profile_url,
				       active, created_date, updated_date
				FROM user_social_account
				WHERE user_id = ? AND active = 1
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
	 * Rakip (izlenen) hesap ekler.
	 * monitored_account global upsert'i yapılır; sonra user_monitored_account bağlantısı kurulur.
	 * Her iki tabloda unique kısıtlar elle kontrol edilir (CLAUDE.md Madde 5).
	 * Endpoint: POST /account/monitored/add
	 */
	@Transactional
	public MonitoredAccountDto addMonitoredAccount(UUID userId, AddMonitoredAccountRequest req) {
		LocalDateTime now = LocalDateTime.now();
		// Platform büyük harf normalize et
		String platformNorm = req.getPlatform().toUpperCase();
		String accountName = req.getAccountName();

		// 1) monitored_account tablosunda bu platform+accountName var mı? (upsert)
		String maCheckSql = """
				SELECT monitored_account_id
				FROM monitored_account
				WHERE platform = ? AND account_name = ? AND active = 1
				""";
		List<UUID> maRows = jdbcTemplate.query(maCheckSql,
				(rs, rowNum) -> rs.getObject("monitored_account_id", UUID.class),
				platformNorm, accountName);

		UUID monitoredAccountId;
		if (maRows.isEmpty()) {
			// Yeni monitored_account kaydı oluştur
			MonitoredAccount ma = new MonitoredAccount();
			ma.setMonitoredAccountId(UUID.randomUUID());
			ma.setPlatform(platformNorm);
			ma.setAccountName(accountName);
			// profileUrl platforma göre otomatik üretilir (INSTAGRAM şimdilik)
			ma.setProfileUrl(buildProfileUrl(platformNorm, accountName));
			ma.setActive(1);
			ma.setCreatedDate(now);
			ma.setUpdatedDate(now);
			// JPA save ile insert
			MonitoredAccount savedMa = monitoredAccountRepository.save(ma);
			monitoredAccountId = savedMa.getMonitoredAccountId();
		} else {
			// Zaten var; mevcut id'yi kullan
			monitoredAccountId = maRows.get(0);
		}

		// 2) Bu kullanıcı bu monitored_account'u zaten takip ediyor mu? (unique kontrol)
		String umaCheckSql = """
				SELECT user_monitored_account_id
				FROM user_monitored_account
				WHERE user_id = ? AND monitored_account_id = ? AND active = 1
				""";
		List<UUID> umaRows = jdbcTemplate.query(umaCheckSql,
				(rs, rowNum) -> rs.getObject("user_monitored_account_id", UUID.class),
				userId, monitoredAccountId);

		// Bağlantı zaten varsa DUPLICATE döndür
		if (!umaRows.isEmpty()) {
			throw new ApiException(ResponseCode.DUPLICATE,
					"Bu hesap zaten izleniyor: " + platformNorm + " / " + accountName);
		}

		// 3) user_monitored_account bağlantısı kur
		UserMonitoredAccount uma = new UserMonitoredAccount();
		uma.setUserMonitoredAccountId(UUID.randomUUID());
		uma.setUserId(userId);
		uma.setMonitoredAccountId(monitoredAccountId);
		uma.setActive(1);
		uma.setCreatedDate(now);
		uma.setUpdatedDate(now);
		// JPA save ile insert
		UserMonitoredAccount savedUma = userMonitoredAccountRepository.save(uma);

		// 4) MonitoredAccountDto oluştur ve döndür
		MonitoredAccountDto dto = new MonitoredAccountDto();
		dto.setUserMonitoredAccountId(savedUma.getUserMonitoredAccountId());
		dto.setMonitoredAccountId(monitoredAccountId);
		dto.setPlatform(platformNorm);
		dto.setAccountName(accountName);
		dto.setProfileUrl(buildProfileUrl(platformNorm, accountName));
		return dto;
	}

	/**
	 * Kullanıcının izlediği (rakip) hesapları listeler.
	 * user_monitored_account ve monitored_account tabloları JOIN'lenir (eski stil =).
	 * Endpoint: POST /account/monitored/list
	 */
	@Transactional(readOnly = true)
	public List<MonitoredAccountDto> listMonitoredAccounts(UUID userId) {
		// Eski stil JOIN: FROM a, b WHERE a.id = b.a_id (CLAUDE.md Madde 6)
		String sql = """
				SELECT uma.user_monitored_account_id,
				       ma.monitored_account_id,
				       ma.platform,
				       ma.account_name,
				       ma.profile_url
				FROM user_monitored_account uma, monitored_account ma
				WHERE uma.user_id = ?
				  AND uma.monitored_account_id = ma.monitored_account_id
				  AND uma.active = 1
				  AND ma.active = 1
				ORDER BY ma.account_name
				""";
		// RowMapper ile MonitoredAccountDto listesi oluştur
		return jdbcTemplate.query(sql, (rs, rowNum) -> {
			MonitoredAccountDto dto = new MonitoredAccountDto();
			dto.setUserMonitoredAccountId(rs.getObject("user_monitored_account_id", UUID.class));
			dto.setMonitoredAccountId(rs.getObject("monitored_account_id", UUID.class));
			dto.setPlatform(rs.getString("platform"));
			dto.setAccountName(rs.getString("account_name"));
			dto.setProfileUrl(rs.getString("profile_url"));
			return dto;
		}, userId);
	}

	/**
	 * Kullanıcının izlediği bir hesabı kaldırır (soft delete: active=0).
	 * Kayıt kullanıcıya ait değilse NOT_FOUND döner.
	 * Endpoint: POST /account/monitored/remove
	 */
	@Transactional
	public DataResponse<Void> removeMonitoredAccount(UUID userId, UUID userMonitoredAccountId) {
		// Kaydın bu kullanıcıya ait ve aktif olduğunu kontrol et
		String checkSql = """
				SELECT user_monitored_account_id, user_id, monitored_account_id, active, created_date, updated_date
				FROM user_monitored_account
				WHERE user_monitored_account_id = ? AND user_id = ? AND active = 1
				""";
		List<UserMonitoredAccount> rows = jdbcTemplate.query(checkSql, (rs, rowNum) -> {
			UserMonitoredAccount uma = new UserMonitoredAccount();
			uma.setUserMonitoredAccountId(rs.getObject("user_monitored_account_id", UUID.class));
			uma.setUserId(rs.getObject("user_id", UUID.class));
			uma.setMonitoredAccountId(rs.getObject("monitored_account_id", UUID.class));
			uma.setActive(rs.getObject("active", Integer.class));
			if (rs.getTimestamp("created_date") != null) {
				uma.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
			}
			if (rs.getTimestamp("updated_date") != null) {
				uma.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
			}
			return uma;
		}, userMonitoredAccountId, userId);

		// Kayıt bulunamazsa veya bu kullanıcıya ait değilse NOT_FOUND döndür
		if (rows.isEmpty()) {
			throw new ApiException(ResponseCode.NOT_FOUND,
					"İzlenen hesap bağlantısı bulunamadı: " + userMonitoredAccountId);
		}

		// Soft delete: active=0 set et, JPA save ile güncelle
		UserMonitoredAccount uma = rows.get(0);
		uma.setActive(0);
		uma.setUpdatedDate(LocalDateTime.now());
		userMonitoredAccountRepository.save(uma);

		// Veri döndürülmez, yalnızca başarı kodu
		return DataResponse.of(ResponseCode.SUCCESS);
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
