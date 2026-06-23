package com.api.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.api.security.JwtAuthenticationFilter;
import com.api.security.RestAuthenticationEntryPoint;

import lombok.RequiredArgsConstructor;

/**
 * Güvenlik yapılandırması (FAZ 1 + FAZ 2).
 * - Stateless (JWT). Session tutulmaz.
 * - /auth/**, /sector/list, swagger, h2-console, actuator/health, actuator/prometheus açık; gerisi korumalı.
 * - JWT filter, UsernamePasswordAuthenticationFilter'dan önce çalışır.
 * - Yetkisiz erişim -> RestAuthenticationEntryPoint (BaseResponse 004, HTTP 200).
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	// İstek başına çalışan JWT doğrulama filtresi
	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	// Yetkisiz erişimde 004 dönen entry point
	private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			// API olduğu için CSRF kapalı
			.csrf(csrf -> csrf.disable())
			// CORS aktif — corsConfigurationSource bean'i kullanılır.
			// Preflight (OPTIONS) istekleri Spring'in CorsFilter'ı tarafından,
			// JWT/authorization filtrelerinden ÖNCE karşılanır (kimlik doğrulaması gerekmez).
			.cors(Customizer.withDefaults())
			// Stateless oturum (JWT)
			.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				// Açık uçlar: kök, swagger, auth ve sektör listesi
				// Actuator: yalnızca health + prometheus (env/beans/info gibi uçlar dışarıya kapalı)
				.requestMatchers(
					"/",
					"/swagger-ui.html",
					"/swagger-ui/**",
					"/v3/api-docs/**",
					"/actuator/health",
					"/actuator/prometheus",
					"/h2-console/**",
					"/auth/**",
					"/api/ping",    // Uygulama canlılık kontrolü — kimlik doğrulaması gerekmez
					"/sector/list",  // Sektör listesi kimlik doğrulaması gerektirmez (onboarding adım 3)
					"/socialagent-test-console.html",
					"/local-paytr-pay.html", // LOCAL sahte ödeme sayfası (statik; yalnız local'de kullanılır)
					"/payment/callback", // PayTR bildirim (callback) ucu — JWT göndermez, "OK" döner
					"/local/**",     // LOCAL iç-test uçları (controller yalnızca local profilde oluşur)
					"/admin/**"      // Admin uçları — JWT yerine X-Admin-Key header ile korumalı (controller doğrular)
						).permitAll()
				// Geri kalan tüm uçlar kimlik doğrulaması ister
				.anyRequest().authenticated()
			)
			// Yetkisiz erişimde özel entry point (HTTP 200 + responseCode 004)
			.exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint))
			// JWT filtresini standart auth filtresinden önce ekle
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			// H2 console iframe için
			.headers(headers -> headers.frameOptions(frame -> frame.disable()));

		return http.build();
	}

	/**
	 * CORS kaynağı — izinli origin'ler app.cors.allowed-origins'ten okunur
	 * (pattern destekli: http://localhost:* gibi). allowCredentials=true ile
	 * birlikte "*" kullanılamayacağı için setAllowedOriginPatterns tercih edilir.
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource(AppProperties appProperties) {
		CorsConfiguration config = new CorsConfiguration();
		// İzinli origin pattern'leri (yml/env'den)
		config.setAllowedOriginPatterns(appProperties.getCors().getAllowedOrigins());
		// Tüm standart HTTP metodları + preflight için OPTIONS
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		// İstek başlıkları (Authorization, Content-Type, X-Admin-Key vb. hepsi)
		config.setAllowedHeaders(List.of("*"));
		// Tarayıcının okuyabileceği yanıt başlıkları
		config.setExposedHeaders(List.of("Authorization"));
		// Cookie/Authorization ile kimlikli istekler
		config.setAllowCredentials(true);
		// Preflight sonucunu 1 saat cache'le
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
