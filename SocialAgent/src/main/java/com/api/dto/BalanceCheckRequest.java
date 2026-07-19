package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Bakiye kontrol isteği (POST /payment/balance-check).
 * Frontend, rapor tipini bildirince mevcut bakiye ve eksik tutar döner.
 */
@Getter
@Setter
public class BalanceCheckRequest {

    // Kontrol edilecek rapor tipi: OWN_ONLY | NONE
    @NotBlank(message = "reportType zorunludur")
    private String reportType;
}
