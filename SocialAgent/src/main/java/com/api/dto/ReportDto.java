package com.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Rapor detayı — Markdown içerik dahil (FAZ 8 — POST /report/detail).
 * Report entity'sinden MapStruct (ReportMapper) ile doldurulur.
 */
@Getter
@Setter
public class ReportDto {

	// Raporun id'si
	private UUID reportId;

	// Raporun ait olduğu job id'si
	private UUID userJobId;

	// Rapor durumu: PENDING | GENERATING | COMPLETED | FAILED
	private String status;

	// Üretilen Markdown rapor metni (COMPLETED ise dolu)
	private String reportContent;

	// Oluşturulma tarihi
	private LocalDateTime createdDate;

	// Son güncellenme tarihi
	private LocalDateTime updatedDate;
}
