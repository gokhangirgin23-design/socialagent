package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.api.entity.RefreshToken;

/**
 * refresh_token JPA repository'si (airepo konvansiyonu).
 * Tekil kayıt CRUD'u için kullanılır; sorgular service'te JdbcTemplate native ile.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
}
