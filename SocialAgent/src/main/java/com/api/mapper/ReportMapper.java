package com.api.mapper;

import org.mapstruct.Mapper;

import com.api.dto.ReportDto;
import com.api.entity.Report;

/**
 * Report entity -> ReportDto dönüşümü için MapStruct mapper'ı (FAZ 8 — rapor detayı).
 * Alan adları entity ve DTO'da aynı olduğundan ek @Mapping gerekmez.
 */
@Mapper(componentModel = "spring")
public interface ReportMapper {

	// Tekil entity -> DTO (Markdown içerik dahil)
	ReportDto toDto(Report report);
}
