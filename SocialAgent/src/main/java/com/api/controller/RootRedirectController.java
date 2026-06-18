package com.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Kök URL (host:port/) -> Swagger UI yönlendirmesi (CLAUDE.md Madde 9).
 * Bu bir tarayıcı yönlendirmesidir; "tüm endpoint'ler POST" kuralı API uçları içindir.
 */
@Tag(name = "Sistem", description = "Kök yönlendirme")
@Controller
public class RootRedirectController {

	@Operation(summary = "Kök → Swagger", description = "Kök URL'yi Swagger arayüzüne yönlendirir.")
	@GetMapping("/")
	public String redirectToSwagger() {
		// Swagger arayüzüne yönlendir
		return "redirect:/swagger-ui.html";
	}
}
