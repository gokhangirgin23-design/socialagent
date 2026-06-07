package com.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcı bazlı rapor listeleme isteği + sayfalama (POST /report/list body) — FAZ 8 dashboard.
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4, Bölüm 12).
 */
@Getter
@Setter
public class ReportListRequest {

	// Sayfa numarası (0'dan başlar); varsayılan 0
	private int page = 0;

	// Sayfa başına kayıt sayısı; varsayılan 10
	private int size = 10;
}
