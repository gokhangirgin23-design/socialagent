package com.api.local;

import java.math.BigDecimal;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.api.config.PaytrProperties;
import com.api.dto.PaytrFormPayload;
import com.api.service.PaytrGateway;

/**
 * LOCAL profilde gerçek PayTR yerine geçen gateway (FAZ PAYMENT — LOCAL).
 *
 * Davranış KALITIMLA değiştirilir (CLAUDE.md Madde 1; LocalDummy* deseniyle aynı):
 *   @Primary @Profile("local") extends PaytrGateway.
 *
 * - buildPaymentForm: form'u www.paytr.com yerine uygulamanın kendi sahte sayfasına
 *   (/local-paytr-pay.html) statik sayfasına GET ile yönlendirir.
 * - verifyCallback: hash'i her zaman geçerli sayar (bypass) → callback'i elle gönderebilirsin.
 */
@Service
@Primary
@Profile("local")
public class LocalPaytrGateway extends PaytrGateway {

    public LocalPaytrGateway(PaytrProperties paytr) {
        super(paytr);
    }

    @Override
    public PaytrFormPayload buildPaymentForm(String merchantOid, String userIp, String email, BigDecimal amount) {
        String paymentAmount = formatAmount(amount);

        PaytrFormPayload payload = new PaytrFormPayload();
        // Gerçek PayTR yerine statik local sahte ödeme sayfası; GET (query string) ile açılır
        payload.setPostUrl("/local-paytr-pay.html");
        payload.setMethod("GET");
        payload.getFields().put("merchant_oid", merchantOid);
        payload.getFields().put("payment_amount", paymentAmount);
        payload.getFields().put("merchant_ok_url", paytr.getOkUrl() != null ? paytr.getOkUrl() : "/");
        payload.getFields().put("merchant_fail_url", paytr.getFailUrl() != null ? paytr.getFailUrl() : "/");
        return payload;
    }

    @Override
    public boolean verifyCallback(String merchantOid, String status, String totalAmount, String incomingHash) {
        // Local: hash doğrulamayı atla → istediğin callback'i elle gönderebilirsin
        return true;
    }
}
