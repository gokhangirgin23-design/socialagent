package com.api.local;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.api.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * LOCAL profil — kredi akışı için test yardımcıları (FAZ CREDIT — LOCAL).
 * Yalnızca local profilde aktif (@Profile("local")). /local/** zaten SecurityConfig'te permitAll.
 *
 * Sahte ödeme SAYFASI artık statik: /local-paytr-pay.html (LocalPaytrGateway oraya GET ile yönlendirir).
 * Bu controller yalnızca kredi test yardımcılarını barındırır.
 */
@RestController
@RequestMapping("/local")
@Profile("local")
@Tag(name = "Local — Ödeme Test", description = "LOCAL profil kredi/ödeme test yardımcıları")
public class LocalPaytrController {

    private final PaymentService paymentService;

    public LocalPaytrController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Test yardımcısı: krediyi elle yükle (PayTR'a hiç girmeden yeterli-kredi yolunu test et). */
    @Operation(summary = "Kredi yükle (local test)",
            description = "Verilen kullanıcının cüzdanına elle kredi ekler; PayTR'a gitmeden yeterli-kredi yolunu test etmek için.")
    @PostMapping("/payment/add-balance")
    public String addBalance(@RequestParam("userId") String userId, @RequestParam("credits") String credits) {
        UUID uid = UUID.fromString(userId);
        paymentService.topupCreditsManual(uid, Integer.parseInt(credits));
        return "creditBalance += " + credits + " for user " + userId;
    }

    /** Test yardımcısı: kredi bakiyesini gör. */
    @Operation(summary = "Kredi bakiyesi gör (local test)",
            description = "Verilen kullanıcının güncel kredi bakiyesini döndürür.")
    @PostMapping("/payment/wallet")
    public String wallet(@RequestParam("userId") String userId) {
        UUID uid = UUID.fromString(userId);
        return "creditBalance=" + paymentService.getCreditBalance(uid);
    }
}
