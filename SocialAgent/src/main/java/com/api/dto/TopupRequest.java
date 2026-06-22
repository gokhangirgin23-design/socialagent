package com.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /payment/topup isteği — rapordan bağımsız bakiye yükleme.
 */
@Getter
@Setter
public class TopupRequest {

    @NotNull(message = "Yükleme tutarı zorunludur")
    @DecimalMin(value = "1.00", message = "Minimum yükleme tutarı 1 TL")
    private BigDecimal amount;
}
