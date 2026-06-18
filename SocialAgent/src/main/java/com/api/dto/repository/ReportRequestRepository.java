package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.ReportRequest;

/**
 * ReportRequest JPA repository'si.
 * Lookup'lar JdbcTemplate native ile yapılır; bu repository yalnızca save için kullanılır.
 */
public interface ReportRequestRepository extends JpaRepository<ReportRequest, UUID> {
    // Ek metod gerekmez; native lookup'lar JdbcTemplate ile ReportRequestService'te yapılır
}
