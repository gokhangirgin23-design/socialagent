package com.api.service;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Tek bir bildirim kanalının (mail/push) gönderim sonucu (FAZ 8 revizyonu).
 * MailSender/PushSender artık void yerine bunu döner; NotificationService
 * bu sonuca göre notification satırına success + error_detail yazar.
 *
 * success=true  -> errorDetail null.
 * success=false -> errorDetail: exception stack trace veya anlamlı sebep cümlesi.
 */
public record SendResult(boolean success, String errorDetail) {

	/** Başarılı gönderim (hata detayı yok). */
	public static SendResult ok() {
		return new SendResult(true, null);
	}

	/** Başarısız gönderim; anlamlı sebep cümlesi ile (exception yoksa). */
	public static SendResult fail(String reason) {
		return new SendResult(false, reason);
	}

	/** Başarısız gönderim; exception'ın tam stack trace'i ile. */
	public static SendResult fail(Throwable ex) {
		StringWriter sw = new StringWriter();
		ex.printStackTrace(new PrintWriter(sw));
		return new SendResult(false, sw.toString());
	}
}
