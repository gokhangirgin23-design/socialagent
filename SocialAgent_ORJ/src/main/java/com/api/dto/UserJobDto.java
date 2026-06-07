package com.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcı job bilgisini istemciye taşıyan DTO.
 * UserJob entity'sinden MapStruct ile doldurulur.
 */
@Getter
@Setter
public class UserJobDto {

	// Job'ın benzersiz id'si
	private UUID userJobId;

	// Job'ı oluşturan kullanıcının id'si
	private UUID userId;

	// Analiz edilecek kendi hesabının id'si (null olabilir)
	private UUID selectedUserSocialAccountId;

	// Analiz modu: OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE
	private String analysisMode;

	// Job periyodu: DAILY | WEEKLY | MONTHLY | ON_DEMAND
	private String jobPeriod;

	// Tekrar analizi önleme penceresi (gün)
	private Integer analysisPeriodDays;

	// Toplam çalışma sayısı (ON_DEMAND'da null)
	private Integer repeatCount;

	// Şu ana kadar çalışma sayısı
	private Integer currentCount;

	// Tamamlandı mı? (0/1)
	private Integer completed;

	// Job'ın oluşturulma tarihi
	private LocalDateTime createdDate;
}
