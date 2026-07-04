package com.api.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * POST /payment/transactions yanıtı — hareket listesi + güncel kredi bakiyesi.
 */
@Getter
@AllArgsConstructor
public class TransactionsResponse {

    private List<TransactionDto> transactions;
    private long creditBalance;
}
