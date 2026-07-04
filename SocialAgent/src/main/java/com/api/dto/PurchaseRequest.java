package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /payment/purchase isteği — kredi paketi satın alma.
 */
@Getter
@Setter
public class PurchaseRequest {

    // Satın alınacak paket kodu: STARTER | STANDARD | PRO | AGENCY
    @NotBlank(message = "packageCode zorunludur")
    private String packageCode;
}
