package com.api.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.config.AppProperties;
import com.api.dto.LoginResponse;
import com.api.dto.RefreshResponse;
import com.api.dto.UserDto;
import com.api.dto.repository.RefreshTokenRepository;
import com.api.dto.repository.UserInfoRepository;
import com.api.entity.RefreshToken;
import com.api.entity.UserInfo;
import com.api.mapper.UserMapper;
import com.api.security.GoogleTokenVerifier;
import com.api.security.GoogleUserData;
import com.api.security.JwtService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kimlik doğrulama servisi (concrete; interface yok - CLAUDE.md Madde 1).
 * Google SSO login, token üretimi ve refresh akışını yönetir.
 * Lookup'lar JdbcTemplate native (airepo loginNative stili); insert/update JpaRepository.
 * Unique kısıtları insert öncesi elle kontrol edilir (CLAUDE.md Madde 5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

	// JdbcTemplate native sorgular için
	private final JdbcTemplate jdbcTemplate;
	// Tekil kayıt CRUD'u için JPA repository'leri
	private final UserInfoRepository userInfoRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	// Google id_token doğrulayıcı
	private final GoogleTokenVerifier googleTokenVerifier;
	// JWT üretici/doğrulayıcı
	private final JwtService jwtService;
	// Entity -> DTO dönüştürücü
	private final UserMapper userMapper;
	// JWT/Google ayarları
	private final AppProperties appProperties;

	// Opak refresh token üretimi için güvenli rastgele kaynak
	private final SecureRandom secureRandom = new SecureRandom();

	/**
	 * Google SSO ile login/register.
	 * id_token doğrulanır -> user_info upsert -> access + refresh üretilip döner.
	 */
	@Transactional
	public LoginResponse googleLogin(String idToken) {
		// 1) id_token doğrula (geçersizse 004 fırlatır)
		GoogleUserData googleUser = googleTokenVerifier.verify(idToken);

		// 2) Kullanıcıyı upsert et (google_id/email unique elle kontrol)
		UserInfo user = upsertUser(googleUser);

		// 3) Access (JWT) + refresh (opak) üret
		String accessToken = jwtService.generateAccessToken(user.getUserId(), user.getEmail());
		String refreshToken = createAndStoreRefreshToken(user.getUserId());

		// 4) Response'u hazırla
		UserDto userDto = userMapper.toDto(user);
		LoginResponse response = new LoginResponse();
		response.setAccessToken(accessToken);
		response.setRefreshToken(refreshToken);
		response.setAccessTokenExpiresInSeconds(jwtService.getAccessTokenExpiresInSeconds());
		response.setUser(userDto);
		return response;
	}

	/**
	 * Geçerli refresh token ile yeni access token üretir.
	 */
	@Transactional(readOnly = true)
	public RefreshResponse refreshAccessToken(String refreshTokenValue) {
		// 1) Refresh token'ı native sorgu ile bul (aktif + süresi geçmemiş)
		RefreshToken stored = findActiveRefreshToken(refreshTokenValue);
		if (stored == null) {
			// Bulunamadı/expired/iptal -> yetkisiz
			throw new ApiException(ResponseCode.UNAUTHORIZED, "Refresh token geçersiz veya süresi dolmuş");
		}

		// 2) İlgili kullanıcıyı native sorgu ile getir
		UserInfo user = findUserById(stored.getUserId());
		if (user == null) {
			throw new ApiException(ResponseCode.UNAUTHORIZED, "Kullanıcı bulunamadı");
		}

		// 3) Yeni access token üret
		String accessToken = jwtService.generateAccessToken(user.getUserId(), user.getEmail());
		RefreshResponse response = new RefreshResponse();
		response.setAccessToken(accessToken);
		response.setAccessTokenExpiresInSeconds(jwtService.getAccessTokenExpiresInSeconds());
		return response;
	}

	/**
	 * userId ile kullanıcıyı DTO olarak döner (korumalı /user/me ucu için).
	 */
	@Transactional(readOnly = true)
	public UserDto getCurrentUser(UUID userId) {
		UserInfo user = findUserById(userId);
		if (user == null) {
			throw new ApiException(ResponseCode.NOT_FOUND, "Kullanıcı bulunamadı");
		}
		return userMapper.toDto(user);
	}

	// ============================================================
	// Yardımcı metodlar
	// ============================================================

	/**
	 * Kullanıcı upsert: önce google_id, yoksa email ile aranır (hesap bağlama),
	 * hiçbiri yoksa yeni kayıt açılır. Unique'ler bu lookup'larla elle kontrol edilir.
	 */
	private UserInfo upsertUser(GoogleUserData googleUser) {
		LocalDateTime now = LocalDateTime.now();

		// 1) google_id ile var mı?
		UserInfo existing = findUserByGoogleId(googleUser.googleId());

		// 2) google_id yoksa email ile var mı? (varsa hesabı bu google_id'ye bağla)
		if (existing == null && googleUser.email() != null) {
			existing = findUserByEmail(googleUser.email());
			if (existing != null) {
				// Mevcut e-posta hesabını Google ile ilişkilendir
				existing.setGoogleId(googleUser.googleId());
			}
		}

		if (existing != null) {
			// Profil bilgilerini Google'dan tazele
			existing.setEmail(googleUser.email());
			existing.setFullName(googleUser.fullName());
			existing.setProfilePhotoUrl(googleUser.profilePhotoUrl());
			existing.setUpdatedDate(now);
			// JPA ile güncelle
			return userInfoRepository.save(existing);
		}

		// 3) Yeni kullanıcı oluştur
		UserInfo newUser = new UserInfo();
		newUser.setUserId(UUID.randomUUID());
		newUser.setGoogleId(googleUser.googleId());
		newUser.setEmail(googleUser.email());
		newUser.setFullName(googleUser.fullName());
		newUser.setProfilePhotoUrl(googleUser.profilePhotoUrl());
		newUser.setActive(1);
		newUser.setCreatedDate(now);
		newUser.setUpdatedDate(now);
		return userInfoRepository.save(newUser);
	}

	/**
	 * Opak refresh token üretir ve refresh_token tablosuna yazar.
	 */
	private String createAndStoreRefreshToken(UUID userId) {
		// 256-bit güvenli rastgele -> base64url (opak, JWT değil)
		byte[] randomBytes = new byte[32];
		secureRandom.nextBytes(randomBytes);
		String tokenValue = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

		LocalDateTime now = LocalDateTime.now();
		// Ömür: gün cinsinden ayardan
		LocalDateTime expireDate = now.plusDays(appProperties.getJwt().getRefreshTokenExpirationDays());

		// Kaydı oluştur
		RefreshToken entity = new RefreshToken();
		entity.setRefreshTokenId(UUID.randomUUID());
		entity.setUserId(userId);
		entity.setToken(tokenValue);
		entity.setExpireDate(expireDate);
		entity.setActive(1);
		entity.setCreatedDate(now);
		entity.setUpdatedDate(now);
		refreshTokenRepository.save(entity);

		return tokenValue;
	}

	// ---------- Native lookup'lar (airepo loginNative stili) ----------

	// UserInfo satırlarını entity'ye çeviren ortak RowMapper
	private static final RowMapper<UserInfo> USER_ROW_MAPPER = (rs, rowNum) -> {
		UserInfo u = new UserInfo();
		u.setUserId(rs.getObject("user_id", UUID.class));
		u.setGoogleId(rs.getString("google_id"));
		u.setEmail(rs.getString("email"));
		u.setFullName(rs.getString("full_name"));
		u.setProfilePhotoUrl(rs.getString("profile_photo_url"));
		u.setSectorId(rs.getObject("sector_id", UUID.class));
		u.setSubsectorId(rs.getObject("subsector_id", UUID.class));
		u.setActive(rs.getObject("active", Integer.class));
		// Tarihleri null güvenli çevir
		if (rs.getTimestamp("created_date") != null) {
			u.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
		}
		if (rs.getTimestamp("updated_date") != null) {
			u.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
		}
		return u;
	};

	// google_id ile kullanıcı (native, ? param)
	private UserInfo findUserByGoogleId(String googleId) {
		// Text-block SQL + ? param (airepo stili)
		String sql = """
				SELECT user_id, google_id, email, full_name, profile_photo_url,
				       sector_id, subsector_id, active, created_date, updated_date
				FROM user_info
				WHERE google_id = ? AND active = 1
				""";
		List<UserInfo> rows = jdbcTemplate.query(sql, USER_ROW_MAPPER, googleId);
		// Tek kayıt beklenir; yoksa null
		return rows.isEmpty() ? null : rows.get(0);
	}

	// email ile kullanıcı (native, ? param)
	private UserInfo findUserByEmail(String email) {
		String sql = """
				SELECT user_id, google_id, email, full_name, profile_photo_url,
				       sector_id, subsector_id, active, created_date, updated_date
				FROM user_info
				WHERE email = ? AND active = 1
				""";
		List<UserInfo> rows = jdbcTemplate.query(sql, USER_ROW_MAPPER, email);
		return rows.isEmpty() ? null : rows.get(0);
	}

	// userId ile kullanıcı (native, ? param)
	private UserInfo findUserById(UUID userId) {
		String sql = """
				SELECT user_id, google_id, email, full_name, profile_photo_url,
				       sector_id, subsector_id, active, created_date, updated_date
				FROM user_info
				WHERE user_id = ? AND active = 1
				""";
		List<UserInfo> rows = jdbcTemplate.query(sql, USER_ROW_MAPPER, userId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	// Aktif ve süresi geçmemiş refresh token (native, ? param)
	private RefreshToken findActiveRefreshToken(String tokenValue) {
		String sql = """
				SELECT refresh_token_id, user_id, token, expire_date,
				       active, created_date, updated_date
				FROM refresh_token
				WHERE token = ? AND active = 1 AND expire_date > ?
				""";
		List<RefreshToken> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
			RefreshToken t = new RefreshToken();
			t.setRefreshTokenId(rs.getObject("refresh_token_id", UUID.class));
			t.setUserId(rs.getObject("user_id", UUID.class));
			t.setToken(rs.getString("token"));
			if (rs.getTimestamp("expire_date") != null) {
				t.setExpireDate(rs.getTimestamp("expire_date").toLocalDateTime());
			}
			t.setActive(rs.getObject("active", Integer.class));
			if (rs.getTimestamp("created_date") != null) {
				t.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
			}
			if (rs.getTimestamp("updated_date") != null) {
				t.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
			}
			return t;
		}, tokenValue, LocalDateTime.now());
		return rows.isEmpty() ? null : rows.get(0);
	}
}
