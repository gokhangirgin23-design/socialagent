package com.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

import com.api.config.PaytrProperties;
import com.api.dto.PaytrFormPayload;
import com.api.util.PaytrTokenUtil;

/**
 * PayTR Direkt API gateway (FAZ PAYMENT) — GERÇEK (prod/test) davranış.
 *
 * Local davranışı interface ile değil KALITIMLA değiştirilir (CLAUDE.md Madde 1):
 *   LocalPaytrGateway extends PaytrGateway (@Primary @Profile("local")).
 * Bu yüzden override edilecek metotlar public/protected ve non-final.
 *
 * Taksit KAPALI: installment_count="0", card_type gönderilmez → tek çekim,
 * total_amount == payment_amount.
 */
@Service
public class PaytrGateway {

    protected final PaytrProperties paytr;

    public PaytrGateway(PaytrProperties paytr) {
        this.paytr = paytr;
    }

    /**
     * STEP 1 — ödeme formu payload'ını üretir.
     * GİZLİ key/salt payload'a KONULMAZ; yalnızca token + public alanlar döner.
     *
     * @param amount tahsil edilecek tutar (deficit) — ör. 123.50
     */
    public PaytrFormPayload buildPaymentForm(String merchantOid, String userIp, String email, BigDecimal amount) {

        String paymentAmount = formatAmount(amount); // "123.50"
        String paymentType = "card";
        String installmentCount = "0";               // taksit kapalı
        String non3d = "0";

        // Token: GİZLİ salt/key burada kullanılır ama PAYLOAD'A YAZILMAZ
        String token = PaytrTokenUtil.generateStep1Token(
                paytr.getMerchantId(), userIp, merchantOid, email, paymentAmount,
                paymentType, installmentCount, paytr.getCurrency(),
                paytr.getTestMode(), non3d,
                paytr.getMerchantSalt(), paytr.getMerchantKey());

        PaytrFormPayload payload = new PaytrFormPayload();
        payload.setPostUrl(paytr.getPostUrl());

        // Sepet (PayTR formatı): [["ad","fiyat",adet], ...]
        String userBasket = "[[\"Spectiqs Rapor\",\"" + paymentAmount + "\",1]]";

        payload.getFields().put("merchant_id", paytr.getMerchantId());
        payload.getFields().put("user_ip", userIp);
        payload.getFields().put("merchant_oid", merchantOid);
        payload.getFields().put("email", email);
        payload.getFields().put("payment_type", paymentType);
        payload.getFields().put("payment_amount", paymentAmount);
        payload.getFields().put("currency", paytr.getCurrency());
        payload.getFields().put("test_mode", paytr.getTestMode());
        payload.getFields().put("non_3d", non3d);
        payload.getFields().put("non3d_test_failed", "0");
        payload.getFields().put("installment_count", installmentCount);
        // card_type GÖNDERİLMEZ (taksit kapalı)
        payload.getFields().put("merchant_ok_url", paytr.getOkUrl());
        payload.getFields().put("merchant_fail_url", paytr.getFailUrl());
        payload.getFields().put("user_name", email);
        payload.getFields().put("user_address", "Spectiqs");
        payload.getFields().put("user_phone", "0000000000");
        payload.getFields().put("user_basket", userBasket);
        payload.getFields().put("debug_on", paytr.getDebugOn());
        payload.getFields().put("client_lang", paytr.getClientLang());
        payload.getFields().put("paytr_token", token);

        return payload;
    }

    /**
     * STEP 2 — callback hash doğrulaması. Local'de override edilip bypass edilir.
     * @return true ise hash geçerli (istek gerçekten PayTR'dan).
     */
    public boolean verifyCallback(String merchantOid, String status, String totalAmount, String incomingHash) {
        return PaytrTokenUtil.verifyCallbackHash(
                merchantOid, status, totalAmount, incomingHash,
                paytr.getMerchantSalt(), paytr.getMerchantKey());
    }

    // PayTR payment_amount: nokta ayraçlı, 2 hane (ör. 123.5 → "123.50")
    protected String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
