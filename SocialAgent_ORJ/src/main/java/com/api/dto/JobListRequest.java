package com.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Job listeleme isteği ile sayfalama parametreleri (POST /job/list body).
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4).
 */
@Getter
@Setter
public class JobListRequest {

	// Sayfa numarası (0'dan başlar); varsayılan 0
	private int page = 0;

	// Sayfa başına kayıt sayısı; varsayılan 10
	private int size = 10;
}
