package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.Subsector;

/**
 * Alt sektör JPA repository'si.
 * Lookup'lar JdbcTemplate native ile yapılır; bu repository yalnızca save/findById için kullanılır.
 * (CLAUDE.md Madde 6 - JPA sadece save/findById)
 */
public interface SubsectorRepository extends JpaRepository<Subsector, UUID> {
	// Ek metod gerekmez; native lookup'lar JdbcTemplate ile SectorService'te yapılır
}
