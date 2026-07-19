package com.api.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.api.config.AppProperties;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E-posta bildirimi gönderen adaptör (FAZ 8 — CLAUDE.md Bölüm 12).
 *
 * NOT: Sınıf adı "AppMailSender" — Spring Boot auto-config "mailSender" bean adıyla
 * çakışmamak için (Boot 4'te bean override kapalı).
 *
 * HTML gövde + opsiyonel PDF eki destekler (MimeMessage).
 * spring.mail.host verilmemişse JavaMailSender bean'i OLUŞMAZ → sessizce atlanır.
 * app.notification.mail-enabled=false ise yine atlanır.
 * Gönderim hatası DIŞARI SIZDIRILMAZ; pipeline ve DB kaydı etkilenmez.
 *
 * Service interface yok (CLAUDE.md Madde 1); @Component concrete adaptör.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppMailSender {

    // JavaMailSender opsiyoneldir: spring.mail.host yoksa bean oluşmaz (ObjectProvider boş döner)
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    // app.notification ayarları (enabled bayrağı, from adresi)
    private final AppProperties appProperties;

    // SMTP kimlik bilgileri: boşsa AuthenticationException oluşmadan erken çıkış yapılır
    @Value("${spring.mail.username:}")
    private String smtpUsername;

    @Value("${spring.mail.password:}")
    private String smtpPassword;

    /**
     * HTML gövdeli, opsiyonel PDF ekli e-posta gönderir.
     *
     * @param to             alıcı e-posta
     * @param subject        konu
     * @param htmlBody       HTML gövde
     * @param pdfAttachment  PDF byte dizisi (null ise ek eklenmez)
     * @param attachFileName PDF dosya adı
     * @return gönderim sonucu
     */
    public SendResult send(String to, String subject, String htmlBody,
                           byte[] pdfAttachment, String attachFileName) {
        // 1) Mail kanalı kapalıysa atla
        if (!appProperties.getNotification().isMailEnabled()) {
            log.debug("Mail bildirimi kapalı (app.notification.mail-enabled=false), atlandı.");
            return SendResult.fail("Mail bildirimi kapalı (app.notification.mail-enabled=false)");
        }
        // 2) Alıcı zorunlu
        if (to == null || to.isBlank()) {
            log.debug("Alıcı e-posta boş, mail gönderilmedi.");
            return SendResult.fail("Alıcı e-posta adresi boş");
        }
        // 3) JavaMailSender bean'i var mı?
        JavaMailSender javaMailSender = mailSenderProvider.getIfAvailable();
        if (javaMailSender == null) {
            log.debug("JavaMailSender yapılandırılmamış (spring.mail.host yok), mail atlandı: to={}", to);
            return SendResult.fail("JavaMailSender yapılandırılmamış (spring.mail.host yok)");
        }
        // 4) SMTP kimlik bilgileri eksikse bağlanmayı dene → AuthenticationException oluşmadan çık
        if (smtpPassword == null || smtpPassword.isBlank()) {
            log.warn("SMTP şifresi tanımlı değil (MAIL_PASSWORD), mail atlandı: to={}", to);
            return SendResult.fail("SMTP şifresi tanımlı değil (MAIL_PASSWORD eksik)");
        }
        // 5) HTML + opsiyonel ek ile MimeMessage gönder
        try {
            MimeMessage mimeMsg = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMsg, true, "UTF-8");

            String from = appProperties.getNotification().getFromAddress();
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(to);
            helper.setSubject(subject);

            String body = (htmlBody != null && !htmlBody.isBlank()) ? htmlBody : "<p>.</p>";
            helper.setText(body, true);

            // Opsiyonel PDF eki
            if (pdfAttachment != null && pdfAttachment.length > 0) {
                String fileName = (attachFileName != null && !attachFileName.isBlank())
                        ? attachFileName : "rapor.pdf";
                helper.addAttachment(fileName,
                        new org.springframework.core.io.ByteArrayResource(pdfAttachment),
                        "application/pdf");
            }

            javaMailSender.send(mimeMsg);
            log.info("Bildirim e-postası gönderildi: to={}, subject={}, ekVar={}",
                    to, subject, (pdfAttachment != null && pdfAttachment.length > 0));
            return SendResult.ok();
        } catch (Exception ex) {
            log.warn("Bildirim e-postası gönderilemedi: to={}, hata={}", to, ex.getMessage());
            return SendResult.fail(ex);
        }
    }

    /**
     * Düz metin mesaj — geriye dönük uyumluluk.
     */
    public SendResult send(String to, String subject, String body) {
        String htmlBody = "<p style=\"font-family:Arial,sans-serif;font-size:14px;\">"
                + escapeHtml(body) + "</p>";
        return send(to, subject, htmlBody, null, null);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
