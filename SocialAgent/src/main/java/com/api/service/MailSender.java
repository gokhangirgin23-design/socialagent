package com.api.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.api.config.AppProperties;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @deprecated AppMailSender kullanın.
 * Spring Boot 4 auto-config "mailSender" bean'iyle isim çakışması nedeniyle
 * @Component kaldırıldı; bu sınıf artık Spring bean olarak kayıtlı değildir.
 */
@Slf4j
@RequiredArgsConstructor
@Deprecated
public class MailSender {

    // JavaMailSender opsiyoneldir: spring.mail.host yoksa bean oluşmaz (ObjectProvider boş döner)
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    // app.notification ayarları (enabled bayrağı, from adresi)
    private final AppProperties appProperties;

    /**
     * HTML gövdeli, opsiyonel PDF ekli e-posta gönderir.
     *
     * @param to             alıcı e-posta
     * @param subject        konu
     * @param htmlBody       HTML gövde (null veya boş ise düz metin fallback kullnılmaz — gönderilmez)
     * @param pdfAttachment  PDF byte dizisi (null ise ek eklenmez)
     * @param attachFileName PDF dosya adı (pdfAttachment null ise dikkate alınmaz)
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
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.debug("JavaMailSender yapılandırılmamış (spring.mail.host yok), mail atlandı: to={}", to);
            return SendResult.fail("JavaMailSender yapılandırılmamış (spring.mail.host yok)");
        }
        // 4) HTML + opsiyonel ek ile MimeMessage gönder
        try {
            MimeMessage mimeMsg = mailSender.createMimeMessage();
            // multipart=true: hem HTML hem ek taşıyabilir
            MimeMessageHelper helper = new MimeMessageHelper(mimeMsg, true, "UTF-8");

            String from = appProperties.getNotification().getFromAddress();
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(to);
            helper.setSubject(subject);

            // HTML gövde (metin versiyonu otomatik çıkarılır)
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

            mailSender.send(mimeMsg);
            log.info("Bildirim e-postası gönderildi: to={}, subject={}, ekVar={}",
                    to, subject, (pdfAttachment != null && pdfAttachment.length > 0));
            return SendResult.ok();
        } catch (Exception ex) {
            log.warn("Bildirim e-postası gönderilemedi: to={}, hata={}", to, ex.getMessage());
            return SendResult.fail(ex);
        }
    }

    /**
     * Eski imza — geriye dönük uyumluluk (e.g. test/admin tarafından çağrılabilir).
     * HTML olmadan düz metin benzeri HTML ile gönderir; ek yok.
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
