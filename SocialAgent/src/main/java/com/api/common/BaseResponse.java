package com.api.common;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Genel response yapısı. Tüm endpoint response'ları bunu extend eder
 * (CLAUDE.md Bölüm 3 / Madde 3).
 * - responseCode: iş sonucu kodu (ResponseCode)
 * - responseDescription: kodun açıklaması
 * Data yoksa alt sınıftaki data alanı null kalır; 404 vb. dönülmez.
 */
@Getter
@Setter
@NoArgsConstructor
public class BaseResponse {

	// İş sonucu kodu (örn. 1 = success)
	private Integer responseCode;
	// İş sonucu açıklaması
	private String responseDescription;

	// ResponseCode enum'undan kod/açıklama set eden yardımcı kurucu
	public BaseResponse(ResponseCode responseCode) {
		this.responseCode = responseCode.getCode();
		this.responseDescription = responseCode.getDescription();
	}

	// Kod/açıklamayı tek satırda set etmek için yardımcı metod
	public void applyCode(ResponseCode responseCode) {
		this.responseCode = responseCode.getCode();
		this.responseDescription = responseCode.getDescription();
	}
}
