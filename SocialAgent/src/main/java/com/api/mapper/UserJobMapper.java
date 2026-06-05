package com.api.mapper;

import java.util.List;

import org.mapstruct.Mapper;

import com.api.dto.UserJobDto;
import com.api.entity.UserJob;

/**
 * UserJob entity <-> UserJobDto dönüşümü için MapStruct mapper'ı.
 * Alan adları entity ve DTO'da aynı olduğundan ek @Mapping gerekmez.
 */
@Mapper(componentModel = "spring")
public interface UserJobMapper {

	// Tekil entity -> DTO dönüşümü
	UserJobDto toDto(UserJob job);

	// Liste dönüşümü (her eleman için toDto çağrılır)
	List<UserJobDto> toDtoList(List<UserJob> jobs);
}
