package com.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Swagger / OpenAPI yapılandırması.
 * UI yolu application.yml'de /swagger-ui.html olarak ayarlı.
 */
@Configuration
public class SwaggerConfig {

	@Bean
	OpenAPI socialAgentOpenApi() {
		// API başlık ve sürüm bilgisi
		return new OpenAPI()
			.info(new Info()
				.title("SocialAgent API")
				.description("Sosyal medya analiz ve raporlama backend API")
				.version("v1"));
	}
}
