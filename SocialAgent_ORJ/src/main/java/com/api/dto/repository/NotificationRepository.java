package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.Notification;

/**
 * notification JPA repository'si (airepo konvansiyonu — FAZ 8).
 * Yalnızca insert (save) için kullanılır; listeleme/okundu işaretleme/sayım
 * JdbcTemplate native ile servis katmanında yapılır (CLAUDE.md Madde 6).
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
	// Ek metod gerekmez; native lookup/update'ler JdbcTemplate ile yapılır
}
