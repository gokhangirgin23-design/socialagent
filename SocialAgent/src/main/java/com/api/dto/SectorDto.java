package com.api.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Sektör bilgisini istemciye taşıyan DTO.
 * Sector entity'sinden MapStruct ile doldurulur.
 */
@Getter
@Setter
public class SectorDto {

	// Sektörün benzersiz id'si
	private UUID sectorId;

	// Sektörün görünen adı (örn. "Teknoloji")
	private String name;
}
