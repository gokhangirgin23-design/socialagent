package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Yeni job oluşturma isteği (POST /job/create body).
 * analysisMode otomatik belirlenir (kullanıcının hesap durumuna göre).
 * analysisPeriodDays istekten alınmaz; backend default 3 uygular.
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4, Bölüm 8).
 */
@Getter
@Setter
public class CreateJobRequest {

	// Job periyodu: DAILY | WEEKLY | MONTHLY | ON_DEMAND (zorunlu)
	@NotBlank(message = "jobPeriod zorunludur")
	private String jobPeriod;

	// Toplam çalışma sayısı; ON_DEMAND ise null/gönderilmez, diğerlerinde girilmesi zorunludur
	private Integer repeatCount;
}
