package com.api.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Satın alınabilir kredi paketi (POST /payment/packages yanıtındaki liste elemanı).
 */
@Getter
@AllArgsConstructor
public class PackageDto {

    private String code;
    private String name;
    private BigDecimal priceTl;
    private int credits;
    private boolean featured;
}
