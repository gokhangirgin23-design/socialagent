package com.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Swagger / OpenAPI yapılandırması.
 * UI yolu application.yml'de /swagger-ui.html olarak ayarlı.
 * "Authorize" butonu ile Bearer JWT girilince tüm endpoint'ler otomatik Authorization header alır.
 */
@Configuration
public class SwaggerConfig {

	@Bean
	OpenAPI socialAgentOpenApi() {
		final String schemeName = "bearerAuth";
		// API başlık/sürüm + JWT Bearer güvenlik şeması
		return new OpenAPI()
			.info(new Info()
				.title("SocialAgent API")
				.description("Sosyal medya analiz ve raporlama backend API")
				.version("v1"))
			// Tüm endpoint'lere varsayılan olarak Bearer auth uygula
			.addSecurityItem(new SecurityRequirement().addList(schemeName))
			.components(new Components()
				.addSecuritySchemes(schemeName, new SecurityScheme()
					.name(schemeName)
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")
					.description("Test konsolundan kopyalanan access token — 'Bearer ' öneki olmadan yapıştır")));
	}
}
