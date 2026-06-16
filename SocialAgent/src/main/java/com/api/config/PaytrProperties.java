package com.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * PayTR Direkt API ayarları (app.paytr.*) — FAZ PAYMENT.
 * GİZLİ değerler (merchant-key, merchant-salt) ASLA repo'ya commit edilmez,
 * ASLA frontend'e/console'a gönderilmez, ASLA loglanmaz — env / secret manager.
 *
 * Standalone @ConfigurationProperties; mevcut AppProperties düzenlenmez.
 */
@Component
@ConfigurationProperties(prefix = "app.paytr")
@Getter
@Setter
public class PaytrProperties {

    // PayTR mağaza no
    private String merchantId;

    // GİZLİ — token üretimi (HMAC key)
    private String merchantKey;

    // GİZLİ — token/hash'e eklenen tuz
    private String merchantSalt;

    // PayTR ödeme formu POST adresi
    private String postUrl = "https://www.paytr.com/odeme";

    // Başarılı/başarısız dönüş adresleri (kullanıcı tarayıcısı buraya döner)
    private String okUrl;
    private String failUrl;

    // PayTR'ın server-to-server bildirim göndereceği callback adresi (panelde de tanımlı olmalı)
    private String callbackUrl;

    // Canlı modda test için 1; gerçek tahsilat için 0
    private String testMode = "0";

    // Entegrasyon hatalarını ekranda görmek için 1 (canlıda 0)
    private String debugOn = "0";

    // Para birimi
    private String currency = "TL";

    // Arayüz dili
    private String clientLang = "tr";

    // LOCAL profil davranışı (LocalPaytrGateway / LocalPaytrController kullanır)
    private Local local = new Local();

    @Getter
    @Setter
    public static class Local {
        // Local'de PayTR'a gitmeden uygulamanın kendi sahte ödeme sayfasına yönlendir
        private boolean enabled = true;
        // Sahte ödeme sayfası açılınca otomatik "success" callback at (manuel tıklamadan)
        private boolean autoSuccess = true;
    }
}
