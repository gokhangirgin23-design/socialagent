package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.UserPayment;

/**
 * user_payment JPA repository — insert/saveAndFlush için.
 * Bakiye okuma/güncelleme JdbcTemplate native ile PaymentService'te yapılır (CLAUDE.md Madde 6).
 */
public interface UserPaymentRepository extends JpaRepository<UserPayment, UUID> {
}
