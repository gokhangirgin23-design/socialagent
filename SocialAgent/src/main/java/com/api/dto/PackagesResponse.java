package com.api.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * POST /payment/packages yanıtı — satın alınabilir paketler + kullanıcının güncel kredi bakiyesi.
 */
@Getter
@AllArgsConstructor
public class PackagesResponse {

    private List<PackageDto> packages;
    private long creditBalance;
}
