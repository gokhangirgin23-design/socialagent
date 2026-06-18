package com.api.local;

import java.math.BigDecimal;
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
 * LOCAL profil — PayTR ödeme akışı için test yardımcıları (FAZ PAYMENT — LOCAL).
 * Yalnızca local profilde aktif (@Profile("local")). /local/** zaten SecurityConfig'te permitAll.
 *
 * Sahte ödeme SAYFASI artık statik: /local-paytr-pay.html (LocalPaytrGateway oraya GET ile yönlendirir).
 * Bu controller yalnızca bakiye test yardımcılarını barındırır.
 */
@RestController
@RequestMapping("/local")
@Profile("local")
@Tag(name = "Local — Ödeme Test", description = "LOCAL profil bakiye/ödeme test yardımcıları")
public class LocalPaytrController {

    private final PaymentService paymentService;

    public LocalPaytrController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Test yardımcısı: bakiyeyi elle yükle (PayTR'a hiç girmeden bakiye-yeterli yolu test et). */
    @Operation(summary = "Bakiye yükle (local test)",
            description = "Verilen kullanıcının cüzdanına elle bakiye ekler; PayTR'a gitmeden bakiye-yeterli yolu test etmek için.")
    @PostMapping("/payment/add-balance")
    public String addBalance(@RequestParam("userId") String userId, @RequestParam("amount") String amount) {
        UUID uid = UUID.fromString(userId);
        paymentService.topupManual(uid, new BigDecimal(amount));
        return "balance += " + amount + " for user " + userId;
    }

    /** Test yardımcısı: bakiyeyi gör. */
    @Operation(summary = "Bakiye gör (local test)",
            description = "Verilen kullanıcının güncel cüzdan bakiyesini döndürür.")
    @PostMapping("/payment/wallet")
    public String wallet(@RequestParam("userId") String userId) {
        UUID uid = UUID.fromString(userId);
        return "balance=" + paymentService.getBalance(uid).toPlainString();
    }
}
