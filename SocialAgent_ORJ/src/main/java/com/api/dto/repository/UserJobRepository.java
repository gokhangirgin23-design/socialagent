package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.UserJob;

/**
 * Kullanıcı job'ı JPA repository'si.
 * Lookup'lar JdbcTemplate native ile yapılır; bu repository yalnızca save/findById için kullanılır.
 * Scheduler da JdbcTemplate ile sorgular (CLAUDE.md Bölüm 9).
 */
public interface UserJobRepository extends JpaRepository<UserJob, UUID> {
	// Ek metod gerekmez; native lookup'lar JdbcTemplate ile JobService/Scheduler'da yapılır
}
