package com.api.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;

/**
 * İskelet doğrulama ucu. "Tüm endpoint'ler POST" ve "BaseResponse" konvansiyonunu örnekler.
 * Uygulama ayakta mı kontrolü için kullanılabilir.
 */
@RestController
@RequestMapping("/api")
public class PingController {

	@PostMapping("/ping")
	public DataResponse<String> ping() {
		// Başarılı response (kod=1 success), data="pong"
		return DataResponse.success("pong");
	}
}
