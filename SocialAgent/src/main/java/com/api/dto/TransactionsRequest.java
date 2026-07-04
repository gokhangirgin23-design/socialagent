package com.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * POST /payment/transactions isteği — basit limit/offset sayfalama.
 */
@Getter
@Setter
public class TransactionsRequest {

    private Integer limit;
    private Integer offset;
}
