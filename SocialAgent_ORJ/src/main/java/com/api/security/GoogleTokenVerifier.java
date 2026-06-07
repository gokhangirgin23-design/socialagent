package com.api.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

import org.springframework.stereotype.Component;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * Google SSO id_token doğrulayıcı.
 * - Prod/test: GoogleIdTokenVerifier ile imza + audience + expiry YEREL doğrulanır.
 * - Local (app.google.verification-enabled=false): imzasız sadece payload çözülür
 *   (geliştirme kolaylığı; gerçek Google token gerekmez). Asla prod'da false yapma.
 *
 * Not: Concrete sınıf (CLAUDE.md "service interface yok" çizgisine uygun).
 */
@Slf4j
@Component
public class GoogleTokenVerifier {

	// Uygulama ayarları (client id + doğrulama bayrağı)
	private final AppProperties appProperties;
	// JSON çözümleme (local bypass payload'u için)
	private final ObjectMapper objectMapper = new ObjectMapper();
	// Gerçek doğrulayıcı (lazy kurulur ki context açılışında ağ erişimi gerekmesin)
	private volatile GoogleIdTokenVerifier verifier;

	public GoogleTokenVerifier(AppProperties appProperties) {
		this.appProperties = appProperties;
	}

	/**
	 * id_token'ı doğrular ve kullanıcı bilgisini döner.
	 * Geçersizse 004 UNAUTHORIZED ile ApiException fırlatılır.
	 */
	public GoogleUserData verify(String idTokenString) {
		// Local geliştirme modu: imza doğrulaması yapmadan payload çöz
		if (!appProperties.getGoogle().isVerificationEnabled()) {
			log.warn("Google id_token imza doğrulaması KAPALI (sadece local/dev için olmalı)");
			return decodePayloadWithoutVerification(idTokenString);
		}
		// Gerçek doğrulama yolu
		return verifyWithGoogle(idTokenString);
	}

	/**
	 * Gerçek doğrulama: Google imzası, audience ve expiry kontrol edilir.
	 */
	private GoogleUserData verifyWithGoogle(String idTokenString) {
		try {
			// Doğrulayıcıyı tek sefer kur (thread-safe lazy init)
			GoogleIdToken idToken = getVerifier().verify(idTokenString);
			// verify(); geçersiz/expired/audience uyumsuz token'da null döner
			if (idToken == null) {
				throw new ApiException(ResponseCode.UNAUTHORIZED, "Google token geçersiz");
			}
			Payload payload = idToken.getPayload();
			// Subject = google_id, diğerleri standart OIDC claim'leri
			return new GoogleUserData(
					payload.getSubject(),
					payload.getEmail(),
					(String) payload.get("name"),
					(String) payload.get("picture"));
		} catch (ApiException ex) {
			// İş hatasını olduğu gibi yukarı ver
			throw ex;
		} catch (Exception ex) {
			// Ağ / parse vb. beklenmeyen durumlar -> yetkisiz say, detayı logla
			log.error("Google id_token doğrulanamadı", ex);
			throw new ApiException(ResponseCode.UNAUTHORIZED, "Google token doğrulanamadı");
		}
	}

	/**
	 * GoogleIdTokenVerifier'ı audience (client id) ile lazy kurar.
	 */
	private GoogleIdTokenVerifier getVerifier() {
		GoogleIdTokenVerifier local = this.verifier;
		if (local == null) {
			synchronized (this) {
				local = this.verifier;
				if (local == null) {
					// Beklenen audience = yapılandırılan OAuth client id
					local = new GoogleIdTokenVerifier.Builder(
							new NetHttpTransport(), GsonFactory.getDefaultInstance())
							.setAudience(Collections.singletonList(appProperties.getGoogle().getClientId()))
							.build();
					this.verifier = local;
				}
			}
		}
		return local;
	}

	/**
	 * LOCAL bypass: JWT'nin orta (payload) parçasını base64url çözer.
	 * İmza KONTROL EDİLMEZ; sadece geliştirme içindir.
	 */
	private GoogleUserData decodePayloadWithoutVerification(String idTokenString) {
		try {
			// JWT formatı: header.payload.signature
			String[] parts = idTokenString.split("\\.");
			if (parts.length < 2) {
				throw new ApiException(ResponseCode.UNAUTHORIZED, "Token formatı hatalı");
			}
			// Orta parçayı base64url decode et
			byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
			JsonNode node = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
			// Claim'leri oku (sub zorunlu)
			String sub = textOrNull(node, "sub");
			if (sub == null) {
				throw new ApiException(ResponseCode.UNAUTHORIZED, "Token 'sub' içermiyor");
			}
			return new GoogleUserData(
					sub,
					textOrNull(node, "email"),
					textOrNull(node, "name"),
					textOrNull(node, "picture"));
		} catch (ApiException ex) {
			throw ex;
		} catch (Exception ex) {
			log.error("Local token payload çözülemedi", ex);
			throw new ApiException(ResponseCode.UNAUTHORIZED, "Token çözülemedi");
		}
	}

	// Null güvenli text claim okuma yardımcı metodu
	private String textOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value == null || value.isNull()) ? null : value.asText();
	}
}
