package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.SocialPost;

/**
 * social_post JPA repository'si (airepo konvansiyonu).
 * Yalnızca insert (save) için kullanılır; dedup ve tekrar-analiz sorguları
 * JdbcTemplate native ile SocialPostService'te yapılır (CLAUDE.md Madde 6).
 */
public interface SocialPostRepository extends JpaRepository<SocialPost, UUID> {
	// Ek metod gerekmez; native lookup'lar JdbcTemplate ile yapılır
}
