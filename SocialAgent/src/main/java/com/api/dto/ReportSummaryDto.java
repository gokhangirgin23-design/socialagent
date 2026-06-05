package com.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Dashboard rapor listesi satırı (FAZ 8 — POST /report/list).
 * Markdown içerik TAŞIMAZ (liste hafif kalsın); içerik /report/detail ile gelir.
 * report ⋈ user_job native join (eski stil "=") ile doldurulur (CLAUDE.md Madde 6).
 */
@Getter
@Setter
public class ReportSummaryDto {

	// Raporun id'si
	private UUID reportId;

	// Raporun ait olduğu job id'si
	private UUID userJobId;

	// Rapor durumu: PENDING | GENERATING | COMPLETED | FAILED
	private String status;

	// Job analiz modu (OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE) — join'den gelir
	private String analysisMode;

	// Job periyodu (DAILY | WEEKLY | MONTHLY | ON_DEMAND) — join'den gelir
	private String jobPeriod;

	// Raporun oluşturulma tarihi
	private LocalDateTime createdDate;

	// Raporun son güncellenme tarihi (durum geçişlerinde değişir)
	private LocalDateTime updatedDate;
}
