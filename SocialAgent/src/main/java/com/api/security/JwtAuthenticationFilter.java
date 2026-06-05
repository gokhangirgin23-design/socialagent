package com.api.security;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Her istekte bir kez çalışan JWT filtresi (stateless).
 * Authorization: Bearer <token> başlığını okur, geçerliyse SecurityContext'e
 * principal olarak userId (UUID) koyar. Geçersiz/eksik token'da context'e
 * dokunmaz; erişim kontrolünü SecurityFilterChain yapar.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	// Bearer başlık öneki
	private static final String BEARER_PREFIX = "Bearer ";

	// JWT doğrulama servisi
	private final JwtService jwtService;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		// Authorization başlığını al
		String header = request.getHeader("Authorization");

		// Bearer token varsa ve henüz kimlik set edilmemişse doğrula
		if (header != null && header.startsWith(BEARER_PREFIX)
				&& SecurityContextHolder.getContext().getAuthentication() == null) {

			// "Bearer " önekini ayıkla
			String token = header.substring(BEARER_PREFIX.length());

			// Token geçerliyse kimliği oluştur
			if (jwtService.isValid(token)) {
				// principal = userId (UUID)
				UUID userId = jwtService.extractUserId(token);
				UsernamePasswordAuthenticationToken authentication =
						new UsernamePasswordAuthenticationToken(
								userId, null, Collections.emptyList());
				// İstek detaylarını ekle
				authentication.setDetails(
						new WebAuthenticationDetailsSource().buildDetails(request));
				// Bağlama yerleştir
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		}

		// Zinciri devam ettir
		filterChain.doFilter(request, response);
	}
}
