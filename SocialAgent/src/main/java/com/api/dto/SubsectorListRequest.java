package com.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Belirli bir sektöre ait alt sektörleri listeleme isteği (POST /subsector/list body).
 * sectorId zorunludur.
 */
@Getter
@Setter
public class SubsectorListRequest {

	// Alt sektörleri listelenecek sektörün id'si (zorunlu)
	@NotNull(message = "sectorId zorunludur")
	private UUID sectorId;
}
