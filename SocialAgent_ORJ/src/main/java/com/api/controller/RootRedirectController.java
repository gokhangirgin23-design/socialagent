package com.api.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Kök URL (host:port/) -> Swagger UI yönlendirmesi (CLAUDE.md Madde 9).
 * Bu bir tarayıcı yönlendirmesidir; "tüm endpoint'ler POST" kuralı API uçları içindir.
 */
@Controller
public class RootRedirectController {

	@GetMapping("/")
	public String redirectToSwagger() {
		// Swagger arayüzüne yönlendir
		return "redirect:/swagger-ui.html";
	}
}
