package com.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.api.config.AppProperties;

/**
 * JwtService için Spring'siz birim testi.
 * Token üretip aynı servisle doğrulanabildiğini ve userId'nin geri çıktığını test eder.
 */
class JwtServiceTest {

	private JwtService jwtService;

	@BeforeEach
	void setUp() {
		// Test için yeterince uzun (>=32 karakter) secret içeren ayar hazırla
		AppProperties props = new AppProperties();
		props.getJwt().setSecret("test-secret-en-az-otuziki-karakter-uzunlugunda-0123456789");
		props.getJwt().setAccessTokenExpirationMinutes(30);
		// Servisi kur ve imzalama anahtarını başlat (@PostConstruct karşılığı)
		jwtService = new JwtService(props);
		jwtService.init();
	}

	@Test
	void uretilenTokenGecerliVeUserIdGeriCikar() {
		UUID userId = UUID.randomUUID();
		// Token üret
		String token = jwtService.generateAccessToken(userId, "test@example.com");
		// Geçerli olmalı
		assertTrue(jwtService.isValid(token));
		// İçinden userId aynen çıkmalı
		assertEquals(userId, jwtService.extractUserId(token));
	}

	@Test
	void bozukTokenGecersizdir() {
		// Anlamsız bir string geçersiz sayılmalı (exception fırlatmadan false)
		assertFalse(jwtService.isValid("bu.bir.token.degil"));
	}
}
