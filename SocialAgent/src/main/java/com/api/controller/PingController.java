package com.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;

/**
 * İskelet doğrulama ucu. "Tüm endpoint'ler POST" ve "BaseResponse" konvansiyonunu örnekler.
 * Uygulama ayakta mı kontrolü için kullanılabilir.
 */
@Tag(name = "Sistem", description = "Sağlık / canlılık kontrolü")
@RestController
@RequestMapping("/api")
public class PingController {

	@Operation(summary = "Canlılık kontrolü", description = "Uygulamanın ayakta olduğunu doğrular (kimlik gerektirmez).")
	@PostMapping("/ping")
	public DataResponse<String> ping() {
		// Başarılı response (kod=1 success), data="pong"
		return DataResponse.success("pong");
	}
}
