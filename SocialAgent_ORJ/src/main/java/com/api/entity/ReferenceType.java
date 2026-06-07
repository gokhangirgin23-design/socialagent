package com.api.entity;

/**
 * Bildirimin işaret ettiği kaynak tipi (CLAUDE.md Bölüm 6, 12 — FAZ 8).
 * notification.reference_type kolonunda String olarak saklanır (entity ilişkisiz; CLAUDE.md Madde 6).
 */
public enum ReferenceType {

	// Bildirim bir rapora işaret eder (reference_id = report_id)
	REPORT,

	// Bildirim bir job'a işaret eder (reference_id = user_job_id)
	JOB
}
