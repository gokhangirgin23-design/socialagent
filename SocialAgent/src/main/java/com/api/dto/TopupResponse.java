package com.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * POST /payment/topup yanıtı — PayTR ödeme formu + merchant_oid.
 * Frontend, paytr.fields["paytr_token"] tokenını alıp PayTR'a yönlenir.
 */
@Getter
@AllArgsConstructor
public class TopupResponse {

    // PayTR idempotency anahtarı (callback'te ödemeyi tanımlar)
    private String merchantOid;

    // PayTR form payload (postUrl + fields; gizli key/salt yok)
    private PaytrFormPayload paytr;
}
