package com.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * /content/create yanıtı.
 * status = QUEUED  → contentRequestId dolu, üretim kuyruğa alındı.
 * status = INSUFFICIENT → bakiye yetersiz; price ve deficit dolu.
 */
@Getter
@Setter
public class ContentCreateResponse {

    private String status;           // QUEUED | INSUFFICIENT
    private UUID contentRequestId;
    private BigDecimal price;
    private BigDecimal deficit;

    public static ContentCreateResponse queued(UUID id, BigDecimal price) {
        ContentCreateResponse r = new ContentCreateResponse();
        r.status = "QUEUED";
        r.contentRequestId = id;
        r.price = price;
        return r;
    }

    public static ContentCreateResponse insufficient(BigDecimal price, BigDecimal balance) {
        ContentCreateResponse r = new ContentCreateResponse();
        r.status = "INSUFFICIENT";
        r.price = price;
        r.deficit = price.subtract(balance);
        return r;
    }
}
