package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ContentEditRequest {

    @NotNull(message = "contentRequestId boş olamaz")
    private UUID contentRequestId;

    @NotBlank(message = "editInstruction boş olamaz")
    @Size(max = 1000, message = "editInstruction en fazla 1000 karakter olabilir")
    private String editInstruction;
}
