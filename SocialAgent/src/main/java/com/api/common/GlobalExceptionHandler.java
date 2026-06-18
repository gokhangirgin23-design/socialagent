package com.api.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * Tüm exception'ları yakalayıp BaseResponse + HTTP 200 olarak döner
 * (CLAUDE.md Madde 3: gerçek/beklenmeyen hatalar dışında her şey 200).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	// İş kuralı hataları (service'ten fırlatılan ApiException)
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<BaseResponse> handleApiException(ApiException ex) {
		// İlgili kodu response'a yansıt
		BaseResponse response = new BaseResponse(ex.getResponseCode());
		response.setResponseDescription(ex.getMessage());
		// HTTP daima 200
		return ResponseEntity.ok(response);
	}

	// @Valid validasyon hataları -> 002 VALIDATION_ERROR
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<BaseResponse> handleValidation(MethodArgumentNotValidException ex) {
		BaseResponse response = new BaseResponse(ResponseCode.VALIDATION_ERROR);
		// İlk validasyon hatasının mesajını açıklama olarak ver
		if (ex.getBindingResult().getFieldError() != null) {
			response.setResponseDescription(ex.getBindingResult().getFieldError().getDefaultMessage());
		}
		return ResponseEntity.ok(response);
	}

	// Bilinmeyen URL (controller eşleşmedi) -> 005 NOT_FOUND, HTTP 200 (CLAUDE.md Madde 3)
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<BaseResponse> handleNoResource(NoResourceFoundException ex) {
		BaseResponse response = new BaseResponse(ResponseCode.NOT_FOUND);
		response.setResponseDescription("İstenen kaynak bulunamadı: " + ex.getResourcePath());
		return ResponseEntity.ok(response);
	}

	// Beklenmeyen tüm hatalar -> 999 SYSTEM_ERROR (yine HTTP 200)
	@ExceptionHandler(Exception.class)
	public ResponseEntity<BaseResponse> handleGeneric(Exception ex) {
		// Beklenmeyen hatayı logla (detay client'a sızmasın)
		log.error("Beklenmeyen hata", ex);
		BaseResponse response = new BaseResponse(ResponseCode.SYSTEM_ERROR);
		return ResponseEntity.ok(response);
	}
}
