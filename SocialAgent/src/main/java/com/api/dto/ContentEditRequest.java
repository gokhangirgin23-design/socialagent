package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ContentEditRequest {

    @NotNull(message = "contentRequestId boş olamaz")
    private UUID contentRequestId;

    @NotBlank(message = "editInstruction boş olamaz")
    private String editInstruction;
}
