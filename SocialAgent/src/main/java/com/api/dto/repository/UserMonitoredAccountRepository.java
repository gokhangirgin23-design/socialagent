package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.UserMonitoredAccount;

/**
 * Kullanıcı-izlenen hesap bağlantısı JPA repository'si.
 * Lookup'lar JdbcTemplate native ile yapılır; bu repository yalnızca save/findById için kullanılır.
 * (CLAUDE.md Madde 6 - JPA sadece save/findById)
 */
public interface UserMonitoredAccountRepository extends JpaRepository<UserMonitoredAccount, UUID> {
	// Ek metod gerekmez; native lookup'lar JdbcTemplate ile AccountService'te yapılır
}
