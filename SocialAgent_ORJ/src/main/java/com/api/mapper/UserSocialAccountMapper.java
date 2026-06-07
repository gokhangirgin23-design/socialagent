package com.api.mapper;

import org.mapstruct.Mapper;

import com.api.dto.UserSocialAccountDto;
import com.api.entity.UserSocialAccount;

/**
 * UserSocialAccount entity <-> UserSocialAccountDto dönüşümü için MapStruct mapper'ı.
 * Alan adları entity ve DTO'da aynı olduğundan ek @Mapping gerekmez.
 * userId alanı DTO'ya dahil edilmemiştir (JWT'den gelir, istemciye tekrar iletilmez).
 */
@Mapper(componentModel = "spring")
public interface UserSocialAccountMapper {

	// Tekil entity -> DTO dönüşümü
	UserSocialAccountDto toDto(UserSocialAccount account);
}
