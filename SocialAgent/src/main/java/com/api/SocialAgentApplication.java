package com.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SocialAgent uygulamasının giriş noktası.
 * @EnableScheduling FAZ 4'te eklendi: JobScheduler @Scheduled metodu aktiftir.
 */
@SpringBootApplication
@EnableScheduling
public class SocialAgentApplication {

	// Uygulamayı başlatan main metodu
	public static void main(String[] args) {
		SpringApplication.run(SocialAgentApplication.class, args);
	}
}
