package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ContentCreateRequest {

    @NotNull(message = "reportId boş olamaz")
    private UUID reportId;

    // POST|STORY|CAROUSEL|REEL|ALL
    @NotBlank(message = "contentType boş olamaz")
    private String contentType;

    // Ürün görseli URL'i (kullanıcı önceden yüklemiş olmalı)
    private String productImageUrl;

    private boolean includeTextInVisual;
}
