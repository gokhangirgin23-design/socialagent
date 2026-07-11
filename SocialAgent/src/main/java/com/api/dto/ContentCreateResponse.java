package com.api.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * /content/create yanıtı.
 * status = QUEUED  → contentRequestId dolu, üretim kuyruğa alındı.
 * status = INSUFFICIENT → kredi yetersiz; creditCost, creditBalance ve missingCredits dolu.
 */
@Getter
@Setter
public class ContentCreateResponse {

    private String status;           // QUEUED | INSUFFICIENT
    private UUID contentRequestId;
    private Integer creditCost;
    private Long creditBalance;
    private Long missingCredits;
    // V11: bu üretim ücretsiz ilk kullanım hakkıyla mı yapıldı?
    private Boolean freeUsage;

    public static ContentCreateResponse queued(UUID id, int creditCost, boolean freeUsage) {
        ContentCreateResponse r = new ContentCreateResponse();
        r.status = "QUEUED";
        r.contentRequestId = id;
        r.creditCost = creditCost;
        r.freeUsage = freeUsage;
        return r;
    }

    public static ContentCreateResponse insufficient(int creditCost, long creditBalance) {
        ContentCreateResponse r = new ContentCreateResponse();
        r.status = "INSUFFICIENT";
        r.creditCost = creditCost;
        r.creditBalance = creditBalance;
        r.missingCredits = (long) creditCost - creditBalance;
        return r;
    }
}
