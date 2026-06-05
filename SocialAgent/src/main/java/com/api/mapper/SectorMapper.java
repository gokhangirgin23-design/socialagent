package com.api.mapper;

import java.util.List;

import org.mapstruct.Mapper;

import com.api.dto.SectorDto;
import com.api.entity.Sector;

/**
 * Sector entity <-> SectorDto dönüşümü için MapStruct mapper'ı.
 * Alan adları entity ve DTO'da aynı olduğundan ek @Mapping gerekmez.
 */
@Mapper(componentModel = "spring")
public interface SectorMapper {

	// Tekil entity -> DTO dönüşümü
	SectorDto toDto(Sector sector);

	// Liste dönüşümü (her eleman için toDto çağrılır)
	List<SectorDto> toDtoList(List<Sector> sectors);
}
