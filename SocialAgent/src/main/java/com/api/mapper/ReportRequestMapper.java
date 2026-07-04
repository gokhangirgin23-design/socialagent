package com.api.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.api.dto.ReportRequestDto;
import com.api.entity.ReportRequest;

/**
 * ReportRequest entity <-> ReportRequestDto dönüşümü için MapStruct mapper'ı.
 * Alan adları entity ve DTO'da aynı olduğundan ek @Mapping gerekmez.
 * FAZ CREDIT: insufficientCredits/requiredCredits/creditBalance alanları entity'de yok; servis manuel doldurur → ignore.
 */
@Mapper(componentModel = "spring")
public interface ReportRequestMapper {

    // Tekil entity -> DTO dönüşümü (kredi kapısı ve rapor bağlantısı alanları entity'de olmadığından ignore edilir)
    @Mapping(target = "insufficientCredits", ignore = true)
    @Mapping(target = "requiredCredits", ignore = true)
    @Mapping(target = "creditBalance", ignore = true)
    @Mapping(target = "reportId", ignore = true)
    ReportRequestDto toDto(ReportRequest request);

    // Liste dönüşümü
    List<ReportRequestDto> toDtoList(List<ReportRequest> requests);
}
