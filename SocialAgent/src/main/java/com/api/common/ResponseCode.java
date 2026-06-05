package com.api.common;

import lombok.Getter;

/**
 * Tüm response'larda kullanılacak iş sonucu kodları (D5 - CLAUDE.md Bölüm 6).
 * HTTP kodu daima 200; sonuç bu enum ile taşınır.
 */
@Getter
public enum ResponseCode {

	// 001 success
	SUCCESS(1, "success"),
	// Validasyon hatası (örn. boş alan)
	VALIDATION_ERROR(2, "validation error"),
	// Unique constraint ihlali / kayıt zaten var
	DUPLICATE(3, "duplicate / unique violation"),
	// Token geçersiz / yetkisiz
	UNAUTHORIZED(4, "unauthorized / token invalid"),
	// İş anlamında bulunamadı (HTTP yine 200, data null)
	NOT_FOUND(5, "not found"),
	// Beklenmeyen sistem hatası
	SYSTEM_ERROR(999, "system error");

	// Kodun integer karşılığı
	private final Integer code;
	// Kodun açıklaması
	private final String description;

	ResponseCode(Integer code, String description) {
		this.code = code;
		this.description = description;
	}
}
