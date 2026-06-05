package com.api.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Alt sektör bilgisini istemciye taşıyan DTO.
 * Subsector entity'sinden MapStruct ile doldurulur.
 */
@Getter
@Setter
public class SubsectorDto {

	// Alt sektörün benzersiz id'si
	private UUID subsectorId;

	// Bağlı sektörün id'si
	private UUID sectorId;

	// Alt sektörün görünen adı (örn. "Mobil Uygulama")
	private String name;
}
