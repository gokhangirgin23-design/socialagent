package com.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ContentRequestDto {

    private UUID contentRequestId;
    private UUID socialAccountId;
    private String contentType;
    private String status;
    private String productImageUrl;
    private boolean includeTextInVisual;
    private String editInstruction;
    private int editCount;
    private int editLimit;

    // Üretilen çıktılar
    private List<String> visualUrls;
    private String caption;
    private String hashtags;
    private String cta;
    private String firstComment;
    private String suggestedPostTime;

    private LocalDateTime createdDate;
    private LocalDateTime processFinishedDate;
    private String processError;
}
