package com.api.entity;

/**
 * Rapor üretim durumu (CLAUDE.md Bölüm 6, 11 — FAZ 7).
 * Durum akışı: PENDING -> GENERATING -> COMPLETED | FAILED.
 * report.status kolonunda String olarak saklanır (entity ilişkisiz; CLAUDE.md Madde 6).
 */
public enum ReportStatus {

	// Rapor kaydı oluşturuldu; üretim henüz başlamadı
	PENDING,

	// OpenAI ile Markdown üretimi sürüyor
	GENERATING,

	// Rapor başarıyla üretildi (report_content dolu)
	COMPLETED,

	// Üretim başarısız (AI yok / hata / boş çıktı)
	FAILED
}
