package com.api.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.api.common.ApiException;
import com.api.common.ResponseCode;

/**
 * SecurityContext'ten o anki kullanıcının id'sini okuyan yardımcı.
 * CLAUDE.md Madde 4: userId DAİMA JWT'den; istekten asla okunmaz.
 */
public final class SecurityUtil {

	// Yardımcı sınıf; örneklenmesin
	private SecurityUtil() {
	}

	/**
	 * O anki kimliği doğrulanmış kullanıcının id'sini döner.
	 * Kimlik yoksa 004 UNAUTHORIZED fırlatır.
	 */
	public static UUID getCurrentUserId() {
		// Aktif güvenlik bağlamından kimliği al
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		// Kimlik yok veya anonim ise yetkisiz
		if (authentication == null || authentication.getPrincipal() == null) {
			throw new ApiException(ResponseCode.UNAUTHORIZED, "Kimlik doğrulanmamış");
		}
		Object principal = authentication.getPrincipal();
		// Filter principal olarak UUID koyar
		if (principal instanceof UUID uuid) {
			return uuid;
		}
		// Beklenmeyen principal tipi -> yetkisiz say
		throw new ApiException(ResponseCode.UNAUTHORIZED, "Geçersiz kimlik");
	}
}
