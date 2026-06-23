package com.api.entity;

/**
 * Rapor isteğinin iş (job) yaşam döngüsü durumu.
 * Durum akışı: PENDING -> PROCESSING -> COMPLETED | PARTIAL | FAILED.
 * report_request.status kolonunda String olarak saklanır (entity ilişkisiz; CLAUDE.md Madde 6).
 *
 * NOT: report.status (ReportStatus) çıktı artefaktının durumudur; bu enum ise işin
 * yaşam döngüsüdür. report satırı henüz yokken (PROCESSING) ve PARTIAL durumunu yalnız bu tutar.
 */
public enum ReportRequestStatus {

	// Oluşturuldu/kuyrukta; worker henüz işlemedi
	PENDING,

	// Worker işliyor (process_started_date set)
	PROCESSING,

	// Rapor üretildi ve tüm postlar analizli (analyzed == total)
	COMPLETED,

	// Rapor üretildi ama eksik analiz var (yutulmuş dış-API hatası; analyzed < total)
	PARTIAL,

	// İşleme sırasında exception kaçtı / kullanılabilir rapor üretilemedi
	FAILED
}
