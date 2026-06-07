package com.api.mapper;

import java.util.List;

import org.mapstruct.Mapper;

import com.api.dto.NotificationDto;
import com.api.entity.Notification;

/**
 * Notification entity <-> NotificationDto dönüşümü için MapStruct mapper'ı (FAZ 8).
 * Alan adları entity ve DTO'da aynı olduğundan ek @Mapping gerekmez.
 */
@Mapper(componentModel = "spring")
public interface NotificationMapper {

	// Tekil entity -> DTO
	NotificationDto toDto(Notification notification);

	// Liste dönüşümü (her eleman için toDto)
	List<NotificationDto> toDtoList(List<Notification> notifications);
}
