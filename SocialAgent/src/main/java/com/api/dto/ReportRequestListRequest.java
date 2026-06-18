package com.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Rapor isteği listeleme body'si (POST /report-request/list).
 * userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4).
 */
@Getter
@Setter
public class ReportRequestListRequest {

    // Sayfa numarası (0'dan başlar); varsayılan 0
    private int page = 0;

    // Sayfa başına kayıt sayısı; varsayılan 10
    private int size = 10;
}
