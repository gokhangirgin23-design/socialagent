package com.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcının sektör seçme isteği (POST /sector/select body).
 * sectorId zorunludur; userId JWT'den alınır, istekten okunmaz (CLAUDE.md Madde 4).
 */
@Getter
@Setter
public class SelectSectorRequest {

	// Seçilmek istenen sektörün id'si (zorunlu)
	@NotNull(message = "sectorId zorunludur")
	private UUID sectorId;
}
