package com.api.dto.repository;

import com.api.entity.ContentRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ContentRequestRepository extends JpaRepository<ContentRequest, UUID> {
}
