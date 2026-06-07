package com.api.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway migration yapılandırması.
 * Spring Boot 4'te Flyway auto-configuration kaldırıldığından manuel tanımlanıyor.
 * Bu @Bean uygulama başlangıcında Spring tarafından ilk nesneler oluşturulurken
 * çağrılır; migrate() tüm endpoint isteklerinden önce tamamlanır.
 */
@Configuration
public class FlywayConfig {

	/**
	 * Flyway nesnesini oluşturur, migration'ları çalıştırır ve döndürür.
	 * DataSource (H2 veya PostgreSQL) Spring tarafından enjekte edilir.
	 */
	@Bean
	Flyway flyway(DataSource dataSource) {
		Flyway flyway = Flyway.configure()
				// DataSource profil bazlı (H2 local, PostgreSQL test/prod)
				.dataSource(dataSource)
				// Migration dosyaları: src/main/resources/db/migration/
				.locations("classpath:db/migration")
				.load();
		// Şema oluştur / eksik migration'ları uygula
		flyway.migrate();
		return flyway;
	}
}
