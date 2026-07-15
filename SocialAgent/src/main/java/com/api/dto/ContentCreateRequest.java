package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ContentCreateRequest {

    // POST|STORY|CAROUSEL|REEL
    @NotBlank(message = "contentType boş olamaz")
    private String contentType;

    // Ürün görseli URL'i (kullanıcı önceden yüklemiş olmalı)
    private String productImageUrl;

    private boolean includeTextInVisual;

    // Kullanıcının kendi bağlı sosyal hesabı (opsiyonel) — doluysa içerik hesap DNA'sıyla üretilir
    private UUID socialAccountId;
}
