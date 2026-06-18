package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.api.entity.UserPaymentLog;

/**
 * user_payment_log JPA repository — insert/saveAndFlush için.
 * merchant_oid ile arama ve durum güncelleme JdbcTemplate native ile yapılır.
 */
public interface UserPaymentLogRepository extends JpaRepository<UserPaymentLog, UUID> {
}
