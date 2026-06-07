package com.api.common;

import lombok.Getter;

/**
 * İş kuralı hatalarını ResponseCode ile taşıyan özel exception.
 * Service katmanında fırlatılır, GlobalExceptionHandler yakalar.
 */
@Getter
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	// Hataya karşılık gelen response kodu
	private final ResponseCode responseCode;

	public ApiException(ResponseCode responseCode) {
		super(responseCode.getDescription());
		this.responseCode = responseCode;
	}

	// Özel mesajla birlikte
	public ApiException(ResponseCode responseCode, String message) {
		super(message);
		this.responseCode = responseCode;
	}
}
