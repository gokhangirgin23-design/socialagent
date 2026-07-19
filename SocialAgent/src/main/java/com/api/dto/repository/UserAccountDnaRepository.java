package com.api.dto.repository;

import com.api.entity.UserAccountDna;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserAccountDnaRepository extends JpaRepository<UserAccountDna, UUID> {
}
