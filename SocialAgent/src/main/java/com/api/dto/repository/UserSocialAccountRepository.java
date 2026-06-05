package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.UserSocialAccount;

/**
 * Kullanıcı sosyal hesabı JPA repository'si.
 * Lookup'lar JdbcTemplate native ile yapılır; bu repository yalnızca save/findById için kullanılır.
 * (CLAUDE.md Madde 6 - JPA sadece save/findById)
 */
public interface UserSocialAccountRepository extends JpaRepository<UserSocialAccount, UUID> {
	// Ek metod gerekmez; native lookup'lar JdbcTemplate ile AccountService'te yapılır
}
