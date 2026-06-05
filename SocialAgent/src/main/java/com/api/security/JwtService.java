package com.api.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.api.config.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * JWT access token üretimi ve doğrulaması (jjwt 0.12.x).
 * - subject = userId
 * - email claim'i bilgi amaçlı taşınır
 * Refresh token JWT değildir; opak değer olup DB'de tutulur (AuthService).
 */
@Slf4j
@Component
public class JwtService {

	// Uygulama ayarları (secret + ömür)
	private final AppProperties appProperties;
	// HMAC imzalama anahtarı (secret'tan türetilir)
	private SecretKey signingKey;

	public JwtService(AppProperties appProperties) {
		this.appProperties = appProperties;
	}

	// Bean kurulduğunda imzalama anahtarını hazırla
	@PostConstruct
	void init() {
		String secret = appProperties.getJwt().getSecret();
		// HS256 için anahtar en az 256 bit (32 byte) olmalı
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(
					"app.jwt.secret en az 32 karakter olmalı (HS256 için 256 bit)");
		}
		this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Verilen kullanıcı için access token üretir.
	 */
	public String generateAccessToken(UUID userId, String email) {
		long now = System.currentTimeMillis();
		// Ömür (dakika) -> milisaniye
		long ttlMs = appProperties.getJwt().getAccessTokenExpirationMinutes() * 60_000L;
		// Token'ı oluştur ve imzala
		return Jwts.builder()
				.subject(userId.toString())   // sub = userId
				.claim("email", email)        // ek bilgi
				.issuedAt(new Date(now))      // iat
				.expiration(new Date(now + ttlMs)) // exp
				.signWith(signingKey)         // HS256 imza
				.compact();
	}

	/**
	 * Token geçerli mi? (imza + süre). Geçersizse false.
	 */
	public boolean isValid(String token) {
		try {
			// Doğrulama denemesi; hata fırlatmazsa geçerli
			parse(token);
			return true;
		} catch (Exception ex) {
			// Geçersiz/expired token; debug seviyesinde logla
			log.debug("Geçersiz JWT: {}", ex.getMessage());
			return false;
		}
	}

	/**
	 * Token'dan userId (subject) çıkarır.
	 */
	public UUID extractUserId(String token) {
		// subject string'ini UUID'ye çevir
		return UUID.fromString(parse(token).getPayload().getSubject());
	}

	/**
	 * Access token'ın yapılandırılmış ömrünü saniye olarak döner.
	 */
	public long getAccessTokenExpiresInSeconds() {
		return appProperties.getJwt().getAccessTokenExpirationMinutes() * 60L;
	}

	// Token'ı imzaya göre çözen ortak yardımcı (geçersizde exception fırlatır)
	private Jws<Claims> parse(String token) {
		return Jwts.parser()
				.verifyWith(signingKey)
				.build()
				.parseSignedClaims(token);
	}
}
