package com.api.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.api.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E-posta bildirimi gönderen adaptör (FAZ 8 — CLAUDE.md Bölüm 12).
 *
 * Felsefe (Apify token / AI key yaklaşımıyla aynı — CLAUDE.md AppProperties notları):
 *   - spring.mail.host verilmemişse Spring Boot JavaMailSender bean'i OLUŞMAZ. Bu yüzden
 *     bağımlılık ObjectProvider ile OPSİYONEL alınır; bean yoksa gönderim sessizce atlanır.
 *   - app.notification.mail-enabled=false ise yine atlanır (local/dev kolaylığı).
 *   - Gönderim sırasında hata olsa bile EXCEPTION DIŞARI SIZMAZ; bildirim DB kaydı ve
 *     pipeline (rapor + iş sonu muhasebesi) etkilenmez.
 *
 * Service interface yok (CLAUDE.md Madde 1); @Component concrete adaptör.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailSender {

	// JavaMailSender opsiyoneldir: spring.mail.host yoksa bean oluşmaz (ObjectProvider boş döner)
	private final ObjectProvider<JavaMailSender> mailSenderProvider;

	// app.notification ayarları (enabled bayrağı, from adresi)
	private final AppProperties appProperties;

	/**
	 * Tek bir düz metin e-posta gönderir. Yapılandırma yoksa veya hata olursa sessizce geçer.
	 *
	 * @param to      alıcı e-posta (boşsa gönderilmez)
	 * @param subject konu
	 * @param body    düz metin gövde
	 */
	public void send(String to, String subject, String body) {
		// 1) Bildirim mail kanalı kapalıysa atla
		if (!appProperties.getNotification().isMailEnabled()) {
			log.debug("Mail bildirimi kapalı (app.notification.mail-enabled=false), atlandı.");
			return;
		}
		// 2) Alıcı yoksa gönderilmez
		if (to == null || to.isBlank()) {
			log.debug("Alıcı e-posta boş, mail gönderilmedi.");
			return;
		}
		// 3) JavaMailSender bean'i var mı? (spring.mail.host verilmemişse yoktur)
		JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
		if (mailSender == null) {
			// Yapılandırma yok -> sessizce atla (uygulama çökmesin)
			log.debug("JavaMailSender yapılandırılmamış (spring.mail.host yok), mail atlandı: to={}", to);
			return;
		}
		try {
			// 4) Basit metin e-postası kur ve gönder
			SimpleMailMessage message = new SimpleMailMessage();
			// from adresi yapılandırmadan; boşsa SMTP default kullanılır
			String from = appProperties.getNotification().getFromAddress();
			if (from != null && !from.isBlank()) {
				message.setFrom(from);
			}
			message.setTo(to);
			message.setSubject(subject);
			message.setText(body);
			mailSender.send(message);
			log.info("Bildirim e-postası gönderildi: to={}, subject={}", to, subject);
		} catch (Exception ex) {
			// Gönderim hatası bildirim akışını bozmasın (CLAUDE.md graceful-degradation felsefesi)
			log.warn("Bildirim e-postası gönderilemedi: to={}, hata={}", to, ex.getMessage());
		}
	}
}
