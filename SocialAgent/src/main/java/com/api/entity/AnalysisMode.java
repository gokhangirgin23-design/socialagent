package com.api.entity;

/**
 * Job'ın hangi hesapları analiz edeceğini belirten mod (CLAUDE.md Bölüm 6, 8).
 * Değer, kullanıcının kendi/rakip hesap durumuna göre otomatik belirlenir.
 */
public enum AnalysisMode {

	// Yalnızca kullanıcının kendi hesabı (+ sektör top 5)
	OWN_ONLY,

	// Yalnızca rakip (monitored) hesaplar
	COMPETITOR_ONLY,

	// Hem kendi hem rakip hesaplar
	BOTH,

	// Ne kendi ne rakip hesap seçilmiş; yalnızca sektör top 5 çekilir
	NONE
}
