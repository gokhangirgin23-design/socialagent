package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.Report;

/**
 * report JPA repository'si (airepo konvansiyonu — FAZ 7).
 * Yalnızca insert (save) için kullanılır; "job'ın raporu var mı" ve durum güncellemeleri
 * JdbcTemplate native ile servis katmanında yapılır (CLAUDE.md Madde 6).
 */
public interface ReportRepository extends JpaRepository<Report, UUID> {
	// Ek metod gerekmez; native lookup/update'ler JdbcTemplate ile yapılır
}
