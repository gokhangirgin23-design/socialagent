package com.api.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.api.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Push bildirimi gönderen adaptör (FAZ 8 — CLAUDE.md Bölüm 12, "Push (FCM vb.)").
 *
 * Bu fazda push, sağlayıcı bağımsız bir STUB'tır: app.notification.push-enabled bayrağıyla
 * açılır/kapanır ve şimdilik yalnızca niyeti loglar. Gerçek FCM/APNs entegrasyonu (cihaz token
 * tablosu + sağlayıcı SDK çağrısı) ileride buraya eklenir; imza değişmeden iç gövde doldurulabilir.
 *
 * Felsefe (mail/Apify/AI ile aynı): yapılandırma yoksa veya hata olursa sessizce geçer; bildirim
 * DB kaydı ve pipeline etkilenmez. Service interface yok (CLAUDE.md Madde 1); @Component concrete.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushSender {

	// app.notification ayarları (push-enabled bayrağı)
	private final AppProperties appProperties;

	/**
	 * Bir kullanıcıya push bildirimi gönderir (stub). Kapalıysa/hata olursa sessizce geçer.
	 *
	 * @param userId  hedef kullanıcı
	 * @param title   başlık
	 * @param message gövde
	 */
	public void send(UUID userId, String title, String message) {
		// Push kanalı kapalıysa atla (varsayılan kapalı; cihaz token altyapısı henüz yok)
		if (!appProperties.getNotification().isPushEnabled()) {
			log.debug("Push bildirimi kapalı (app.notification.push-enabled=false), atlandı.");
			return;
		}
		try {
			// TODO(FCM): Gerçek entegrasyon — kullanıcının cihaz token'larını çek (örn. device_token
			// tablosu) ve FCM/APNs sağlayıcısına gönder. Şimdilik niyet loglanır (uygulama çökmesin).
			log.info("Push bildirimi (stub) hazırlandı: userId={}, title={}", userId, title);
		} catch (Exception ex) {
			// Push hatası bildirim akışını bozmasın
			log.warn("Push bildirimi gönderilemedi: userId={}, hata={}", userId, ex.getMessage());
		}
	}
}
