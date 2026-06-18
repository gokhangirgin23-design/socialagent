package com.api.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PayTR Direkt API HMAC-SHA256 token/hash yardımcıları (FAZ PAYMENT).
 * Formüller PayTR örnek kodlarından birebir alındı:
 *
 * STEP 1 token:
 *   base64( HMAC_SHA256( merchant_id + user_ip + merchant_oid + email + payment_amount
 *                        + payment_type + installment_count + currency + test_mode + non_3d
 *                        + merchant_salt , merchant_key ) )
 *
 * STEP 2 callback hash:
 *   base64( HMAC_SHA256( merchant_oid + merchant_salt + status + total_amount , merchant_key ) )
 *
 * merchant_salt/merchant_key GİZLİDİR; PayTR callback'te salt'ı GÖNDERMEZ.
 */
public final class PaytrTokenUtil {

    private PaytrTokenUtil() {
    }

    /** STEP 1 — ödeme formu için paytr_token üretir. */
    public static String generateStep1Token(String merchantId, String userIp, String merchantOid,
            String email, String paymentAmount, String paymentType, String installmentCount,
            String currency, String testMode, String non3d, String merchantSalt, String merchantKey) {

        // Sıra ÇOK önemli — birebir bu sırada birleştirilmeli
        String hashStr = merchantId + userIp + merchantOid + email + paymentAmount
                + paymentType + installmentCount + currency + testMode + non3d;
        return hmacSha256Base64(hashStr + merchantSalt, merchantKey);
    }

    /** STEP 2 — gelen hash ile beklenen hash'i sabit zamanlı karşılaştırır. */
    public static boolean verifyCallbackHash(String merchantOid, String status, String totalAmount,
            String incomingHash, String merchantSalt, String merchantKey) {

        String expected = generateCallbackHash(merchantOid, status, totalAmount, merchantSalt, merchantKey);
        return constantTimeEquals(expected, incomingHash);
    }

    /** STEP 2 — beklenen callback hash'ini üretir. */
    public static String generateCallbackHash(String merchantOid, String status, String totalAmount,
            String merchantSalt, String merchantKey) {

        String base = merchantOid + merchantSalt + status + totalAmount;
        return hmacSha256Base64(base, merchantKey);
    }

    // HMAC-SHA256 → base64
    private static String hmacSha256Base64(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("PayTR token üretilemedi", e);
        }
    }

    // Sabit zamanlı karşılaştırma (null güvenli)
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < x.length; i++) {
            result |= x[i] ^ y[i];
        }
        return result == 0;
    }
}
