package com.api.mapper;

import java.util.List;

import org.mapstruct.Mapper;

import com.api.dto.SubsectorDto;
import com.api.entity.Subsector;

/**
 * Subsector entity <-> SubsectorDto dönüşümü için MapStruct mapper'ı.
 * Alan adları entity ve DTO'da aynı olduğundan ek @Mapping gerekmez.
 */
@Mapper(componentModel = "spring")
public interface SubsectorMapper {

	// Tekil entity -> DTO dönüşümü
	SubsectorDto toDto(Subsector subsector);

	// Liste dönüşümü (her eleman için toDto çağrılır)
	List<SubsectorDto> toDtoList(List<Subsector> subsectors);
}
