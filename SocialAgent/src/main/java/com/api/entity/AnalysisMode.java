package com.api.entity;

/**
 * Job'ın hangi hesapları analiz edeceğini belirten mod (CLAUDE.md Bölüm 6, 8).
 * Rakip hesap özelliğinin kaldırılmasıyla COMPETITOR_ONLY ve BOTH silindi — create() üzerinden
 * artık yalnızca OWN_ONLY üretilir; NONE (sektör top 5, kendi hesap yok) create() üzerinden
 * üretilemez ama teknik/geriye dönük bir değer olarak enum'da kalır.
 */
public enum AnalysisMode {

	// Kullanıcının kendi hesabı + sektör top 5
	OWN_ONLY,

	// Ne kendi hesap seçilmiş; yalnızca sektör top 5 çekilir
	NONE
}
