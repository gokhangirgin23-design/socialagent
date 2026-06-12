package com.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Bir job'ın AI tarafından üretilen Markdown raporu (report tablosu — CLAUDE.md Bölüm 5, 11).
 * FAZ 7'de üretilir: job'a ait tüm post_analysis JSON'ları toplanır -> OpenAI rapor prompt'u ->
 * Markdown -> report_content. status PENDING -> GENERATING -> COMPLETED/FAILED akışını izler.
 *
 * İlişkiler nesne referansı ile değil, yalnızca ID kolonu ile tutulur (CLAUDE.md Madde 6).
 * Her job için tek rapor tutulur; sonraki çalışmalarda aynı kayıt yenilenir (FAZ 7 — ReportService).
 */
@Entity
@Table(name = "report")
@Getter
@Setter
public class Report {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "report_id")
	private UUID reportId;

	// Raporun ait olduğu rapor isteği (nesne referansı yok - CLAUDE.md Madde 6)
	@Column(name = "request_id")
	private UUID requestId;

	// Rapor durumu: PENDING | GENERATING | COMPLETED | FAILED (ReportStatus enum)
	@Column(name = "status")
	private String status;

	// Üretilen Markdown rapor metni (COMPLETED olunca dolar)
	@Column(name = "report_content")
	private String reportContent;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi (her durum geçişinde güncellenir)
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
