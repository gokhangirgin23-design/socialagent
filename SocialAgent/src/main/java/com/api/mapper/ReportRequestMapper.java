package com.api.mapper;

import java.util.List;

import org.mapstruct.Mapper;

import com.api.dto.ReportRequestDto;
import com.api.entity.ReportRequest;

/**
 * ReportRequest entity <-> ReportRequestDto dönüşümü için MapStruct mapper'ı.
 * Alan adları entity ve DTO'da aynı olduğundan ek @Mapping gerekmez.
 */
@Mapper(componentModel = "spring")
public interface ReportRequestMapper {

    // Tekil entity -> DTO dönüşümü
    ReportRequestDto toDto(ReportRequest request);

    // Liste dönüşümü
    List<ReportRequestDto> toDtoList(List<ReportRequest> requests);
}
