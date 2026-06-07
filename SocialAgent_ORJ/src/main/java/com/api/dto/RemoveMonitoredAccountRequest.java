package com.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Rakip (izlenen) hesabı kaldırma isteği (POST /account/monitored/remove body).
 * userMonitoredAccountId zorunludur; userId JWT'den alınır (CLAUDE.md Madde 4).
 * Kaldırma işlemi soft delete'tir; aktif=0 yapılır.
 */
@Getter
@Setter
public class RemoveMonitoredAccountRequest {

	// Kaldırılacak kullanıcı-hesap bağlantı kaydının id'si (zorunlu)
	@NotNull(message = "userMonitoredAccountId zorunludur")
	private UUID userMonitoredAccountId;
}
