package com.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Dashboard rapor listesi satırı (POST /report/list).
 * Markdown içerik TAŞIMAZ (liste hafif kalsın); içerik /report/detail ile gelir.
 * report ⋈ report_request native join (eski stil "=") ile doldurulur (CLAUDE.md Madde 6).
 */
@Getter
@Setter
public class ReportSummaryDto {

    // Raporun id'si
    private UUID reportId;

    // Raporun ait olduğu rapor isteği id'si
    private UUID requestId;

    // Rapor durumu: PENDING | GENERATING | COMPLETED | FAILED
    private String status;

    // Rapor türü (OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE) — join'den gelir
    private String reportType;

    // Raporun oluşturulma tarihi
    private LocalDateTime createdDate;

    // Raporun son güncellenme tarihi (durum geçişlerinde değişir)
    private LocalDateTime updatedDate;
}
