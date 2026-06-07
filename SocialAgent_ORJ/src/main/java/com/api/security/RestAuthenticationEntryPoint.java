package com.api.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.api.common.BaseResponse;
import com.api.common.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
/**
 * Kimlik doğrulaması gereken bir uca token'sız/geçersiz token ile erişilirse
 * devreye girer. CLAUDE.md Madde 3 gereği gövde BaseResponse (responseCode=004),
 * HTTP status yine 200 olur (gerçek exception değil; iş sonucu koddan okunur).
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	// ObjectMapper thread-safe; context'ten enjekte etmek yerine yerel örnek
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		// 004 UNAUTHORIZED gövdesi hazırla
		BaseResponse body = new BaseResponse(ResponseCode.UNAUTHORIZED);
		// HTTP daima 200; sonuç responseCode ile taşınır
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		// Gövdeyi yaz
		objectMapper.writeValue(response.getWriter(), body);
	}
}
