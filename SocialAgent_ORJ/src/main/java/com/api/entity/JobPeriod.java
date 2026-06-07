package com.api.entity;

/**
 * Job'ın çalışma periyodu (CLAUDE.md Bölüm 6, 8).
 * ON_DEMAND anlık çalışır; repeat_count kullanılmaz.
 * Diğer değerlerde scheduler tarafından periyodik tetiklenir.
 */
public enum JobPeriod {

	// Her gün çalışır
	DAILY,

	// Her hafta çalışır
	WEEKLY,

	// Her ay çalışır
	MONTHLY,

	// Anlık; tek seferlik; repeat_count gerekmez; tamamlanınca completed=1
	ON_DEMAND
}
