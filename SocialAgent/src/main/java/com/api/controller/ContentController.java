package com.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.common.ResponseCode;
import com.api.dto.ContentCreateRequest;
import com.api.dto.ContentEditRequest;
import com.api.dto.ContentListRequest;
import com.api.dto.ContentRequestDto;
import com.api.security.SecurityUtil;
import com.api.service.ContentRequestService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * İçerik üretim endpoint'leri.
 * Rapor bazlı görsel + caption üretimi; "Görsel Üretimlerim" sayfa verisi.
 * Hepsi POST (CLAUDE.md Madde 2); JWT zorunlu.
 */
@Tag(name = "İçerik Üretimi", description = "Rapor bazlı görsel ve caption üretimi")
@RestController
@RequestMapping("/content")
@RequiredArgsConstructor
public class ContentController {

    private final ContentRequestService contentRequestService;

    /**
     * Rapor üzerinden yeni içerik üretim isteği başlatır.
     * Endpoint: POST /content/create
     */
    @Operation(summary = "İçerik üretim isteği oluştur",
            description = "Seçilen rapor ve içerik tipine göre görsel + caption üretimini başlatır.")
    @PostMapping("/create")
    public DataResponse<UUID> create(@Valid @RequestBody ContentCreateRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        UUID contentRequestId = contentRequestService.create(userId, request);
        return DataResponse.success(contentRequestId);
    }

    /**
     * Mevcut içeriği düzenleme talimatıyla yeniden kuyruğa basar.
     * Endpoint: POST /content/edit
     */
    @Operation(summary = "İçeriği düzenle",
            description = "Düzenleme talimatıyla mevcut içerik isteğini yeniden kuyruğa basar (maks 3 hak).")
    @PostMapping("/edit")
    public DataResponse<Void> edit(@Valid @RequestBody ContentEditRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        contentRequestService.edit(userId, request);
        return DataResponse.success(null);
    }

    /**
     * Kullanıcının içerik üretimlerini listeler ("Görsel Üretimlerim" sayfası).
     * Endpoint: POST /content/list
     */
    @Operation(summary = "İçerik üretimlerini listele",
            description = "Kullanıcının tüm içerik üretim isteklerini durum bilgisiyle sayfalı döndürür.")
    @PostMapping("/list")
    public DataResponse<List<ContentRequestDto>> list(
            @RequestBody(required = false) ContentListRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        int page = (request != null) ? request.getPage() : 0;
        int size = (request != null) ? request.getSize() : 10;
        List<ContentRequestDto> result = contentRequestService.list(userId, page, size);
        return DataResponse.success(result);
    }

    /**
     * Tek bir içerik isteğinin detayını döner.
     * Endpoint: POST /content/detail
     */
    @Operation(summary = "İçerik üretim detayı",
            description = "Belirtilen içerik isteğinin görsel URL'leri, caption ve diğer çıktılarını döndürür.")
    @PostMapping("/detail")
    public DataResponse<ContentRequestDto> detail(@RequestBody ContentDetailRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        ContentRequestDto dto = contentRequestService.detail(userId, request.getContentRequestId());
        if (dto == null) {
            return DataResponse.of(ResponseCode.NOT_FOUND);
        }
        return DataResponse.success(dto);
    }

    // İç DTO — yalnızca bu controller'da kullanılır
    public static class ContentDetailRequest {
        private UUID contentRequestId;
        public UUID getContentRequestId() { return contentRequestId; }
        public void setContentRequestId(UUID contentRequestId) { this.contentRequestId = contentRequestId; }
    }
}
