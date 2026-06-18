package com.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * STEP 1 sonucu: frontend'in PayTR'a (ya da local sahte sayfaya) POST edeceği form verisi.
 * GİZLİ key/salt BURADA YOKTUR — sadece token ve formun POST'layacağı public alanlar döner.
 * Kart alanları (cc_owner/card_number/cvv...) bu payload'da yok; kullanıcı formda kendisi
 * girer ve form DOĞRUDAN PayTR'a POST eder (kart verisi backend'e hiç gelmez).
 */
@Getter
@Setter
public class PaytrFormPayload {

    // Formun POST edileceği adres (prod: https://www.paytr.com/odeme, local: /local-paytr-pay.html)
    private String postUrl;

    // Gönderim yöntemi: prod PayTR "POST" (gizli form), local statik sayfa "GET" (query string)
    private String method = "POST";

    // PayTR'ın beklediği hidden form alanları (token dahil)
    private Map<String, String> fields = new LinkedHashMap<>();
}
