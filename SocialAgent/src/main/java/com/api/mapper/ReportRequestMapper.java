package com.api.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.api.dto.ReportRequestDto;
import com.api.entity.ReportRequest;

/**
 * ReportRequest entity <-> ReportRequestDto dönüşümü için MapStruct mapper'ı.
 * Alan adları entity ve DTO'da aynı olduğundan ek @Mapping gerekmez.
 * FAZ PAYMENT: paymentRequired/amountToPay/paytr alanları entity'de yok; servis manuel doldurur → ignore.
 */
@Mapper(componentModel = "spring")
public interface ReportRequestMapper {

    // Tekil entity -> DTO dönüşümü (ödeme ve rapor bağlantısı alanları entity'de olmadığından ignore edilir)
    @Mapping(target = "paymentRequired", ignore = true)
    @Mapping(target = "amountToPay", ignore = true)
    @Mapping(target = "paytr", ignore = true)
    @Mapping(target = "reportId", ignore = true)
    ReportRequestDto toDto(ReportRequest request);

    // Liste dönüşümü
    List<ReportRequestDto> toDtoList(List<ReportRequest> requests);
}
