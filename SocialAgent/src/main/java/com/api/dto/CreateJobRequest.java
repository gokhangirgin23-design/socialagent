package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Yeni job oluşturma isteği (POST /job/create body).
 * analysisMode otomatik belirlenir (kullanıcının hesap durumuna göre).
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4, Bölüm 8).
 */
@Getter
@Setter
public class CreateJobRequest {

	// Job periyodu: DAILY | WEEKLY | MONTHLY | ON_DEMAND (zorunlu)
	@NotBlank(message = "jobPeriod zorunludur")
	private String jobPeriod;

	// Tekrar analizi önleme penceresi (gün); null ise varsayılan 7 uygulanır
	private Integer analysisPeriodDays;

	// Toplam çalışma sayısı; ON_DEMAND ise null bırakılır, diğerlerinde girilmesi zorunludur
	private Integer repeatCount;
}
