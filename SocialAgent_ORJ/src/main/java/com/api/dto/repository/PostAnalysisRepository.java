package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.PostAnalysis;

/**
 * post_analysis JPA repository'si (airepo konvansiyonu — FAZ 6).
 * Yalnızca insert (save) için kullanılır; "analizi olmayan post" ve "zaten analiz var mı"
 * sorguları JdbcTemplate native ile servis katmanında yapılır (CLAUDE.md Madde 6).
 */
public interface PostAnalysisRepository extends JpaRepository<PostAnalysis, UUID> {
	// Ek metod gerekmez; native lookup'lar JdbcTemplate ile yapılır
}
