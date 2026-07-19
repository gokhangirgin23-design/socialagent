package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.UserFreeUsage;

/**
 * UserFreeUsage JPA repository'si.
 * Lookup'lar JdbcTemplate native ile FreeUsageService'te yapılır; bu repository save/exists için.
 */
public interface UserFreeUsageRepository extends JpaRepository<UserFreeUsage, UUID> {
}
