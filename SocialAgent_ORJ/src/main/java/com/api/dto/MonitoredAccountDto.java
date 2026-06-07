package com.api.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * İzlenen (rakip) hesap bilgisini istemciye taşıyan DTO.
 * user_monitored_account ve monitored_account tabloları JdbcTemplate JOIN'i ile doldurulur;
 * MapStruct kullanılmaz (CLAUDE.md Madde 6 - join sonucu RowMapper ile üretilir).
 */
@Getter
@Setter
public class MonitoredAccountDto {

	// Kullanıcı-hesap bağlantı kaydının id'si (user_monitored_account)
	private UUID userMonitoredAccountId;

	// İzlenen hesabın global id'si (monitored_account)
	private UUID monitoredAccountId;

	// Hesabın bulunduğu platform (örn. "INSTAGRAM")
	private String platform;

	// Hesap kullanıcı adı
	private String accountName;
}
