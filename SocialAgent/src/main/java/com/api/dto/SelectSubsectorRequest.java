package com.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcının alt sektör seçme isteği (POST /subsector/select body).
 * subsectorId zorunludur; userId JWT'den alınır (CLAUDE.md Madde 4).
 */
@Getter
@Setter
public class SelectSubsectorRequest {

	// Seçilmek istenen alt sektörün id'si (zorunlu)
	@NotNull(message = "subsectorId zorunludur")
	private UUID subsectorId;
}
