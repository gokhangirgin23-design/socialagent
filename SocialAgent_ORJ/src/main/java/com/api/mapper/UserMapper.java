package com.api.mapper;

import org.mapstruct.Mapper;

import com.api.dto.UserDto;
import com.api.entity.UserInfo;

/**
 * UserInfo entity'sini istemciye dönülen UserDto'ya çeviren MapStruct mapper'ı.
 * Spring bean olarak üretilir (componentModel = "spring").
 */
@Mapper(componentModel = "spring")
public interface UserMapper {

	// Alan adları birebir aynı olduğu için ek eşleme gerekmez
	UserDto toDto(UserInfo userInfo);
}
