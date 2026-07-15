package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContentCreateRequest {

    // POST|STORY|CAROUSEL|REEL
    @NotBlank(message = "contentType boş olamaz")
    private String contentType;

    // Ürün görseli URL'i (kullanıcı önceden yüklemiş olmalı)
    private String productImageUrl;

    private boolean includeTextInVisual;

    // NOT: socialAccountId artık istekten alınmaz — kullanıcının bağlı hesabı (varsa) servis
    // katmanında userId'den otomatik bulunur (bkz. ContentRequestService.resolveOwnSocialAccountId).
}
