package com.api.dto.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.api.entity.UserInfo;

/**
 * user_info JPA repository'si (airepo konvansiyonu).
 * Tekil kayıt CRUD'u (save/findById) için kullanılır.
 * İlişkili / sorgu odaklı erişimler service'te JdbcTemplate native ile yapılır.
 */
@Repository
public interface UserInfoRepository extends JpaRepository<UserInfo, UUID> {
}
