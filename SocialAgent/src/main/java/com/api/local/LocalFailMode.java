package com.api.local;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * LOCAL profil failure enjeksiyon anahtarı (sadece iç test — @Profile("local")).
 * Dummy bean'ler (Apify/AI/Report) bu holder'ı kontrol edip deterministik hata üretir;
 * böylece "18/20 timeout", "Apify boş", "rapor üretilemedi" gibi failure path'leri
 * canlı bir senaryoymuş gibi tetikleyebilirsin.
 *
 * Kullanım: LocalFailModeController ile arm/clear; dummy içinde fire(mode) çağrılır.
 *  - count verilmezse (null/0): mod temizlenene kadar sürer (limitsiz)
 *  - count verilirse: yalnız sıradaki N ilgili çağrı için geçerlidir, sonra otomatik NONE'a döner
 *
 * Diğer ortamlarda bu bean hiç oluşmaz; gerçek bean'ler aynen çalışır.
 */
@Component
@Profile("local")
public class LocalFailMode {

	/** Failure modları + gerçek-dünya karşılığı (dummy'lerde fire ile kontrol edilir). */
	public enum Mode {
		// Hiçbir hata enjekte edilmiyor (varsayılan)
		NONE,
		// fetchPostsByUrls boş liste döner -> GERÇEK Apify timeout'u simülasyonu (prod yutar, boş döner)
		APIFY_EMPTY,
		// fetchPostsByUrls exception fırlatır -> beklenmedik scrape hatası -> processRequest FAILED
		APIFY_THROW,
		// analyzeFull null döner -> her iki model de timeout simülasyonu -> ilgili post atlanır -> PARTIAL
		AI_RETURN_NULL,
		// analyzeFull exception fırlatır -> yakalanmamış analiz hatası -> analyzeRequest rollback + FAILED
		AI_THROW,
		// generateReport false döner -> kullanılabilir rapor yok -> FAILED
		REPORT_NULL,
		// generateReport exception fırlatır -> FAILED
		REPORT_THROW
	}

	// Aktif mod (volatile: farklı thread'lerden okunabilir)
	private volatile Mode mode = Mode.NONE;
	// Limitsiz mi? (count verilmediyse true)
	private volatile boolean unlimited = false;
	// Kalan tetikleme sayısı (sınırlı modda)
	private final AtomicInteger remaining = new AtomicInteger(0);

	/**
	 * Bir failure modunu kurar.
	 *
	 * @param m     mod (null -> NONE)
	 * @param count kaç ilgili çağrı için geçerli; null/0/negatif -> limitsiz
	 */
	public synchronized void arm(Mode m, Integer count) {
		this.mode = (m == null) ? Mode.NONE : m;
		if (count == null || count <= 0) {
			this.unlimited = true;
			this.remaining.set(0);
		} else {
			this.unlimited = false;
			this.remaining.set(count);
		}
	}

	/** Failure modunu temizler (normale döner). */
	public synchronized void clear() {
		this.mode = Mode.NONE;
		this.unlimited = false;
		this.remaining.set(0);
	}

	/** Aktif mod. */
	public Mode current() {
		return mode;
	}

	/** Kalan tetikleme (limitsizse -1). */
	public int remaining() {
		return unlimited ? -1 : remaining.get();
	}

	/**
	 * Verilen mod aktifse true döndürür ve sınırlı moddaysa sayacı tüketir.
	 * Sayaç biterse mod otomatik NONE'a döner (sonraki çağrılar normal çalışır).
	 *
	 * @param m kontrol edilen mod
	 * @return bu çağrı için hata enjekte edilmeli mi
	 */
	public synchronized boolean fire(Mode m) {
		if (mode != m) {
			return false;
		}
		if (unlimited) {
			return true;
		}
		if (remaining.get() <= 0) {
			return false;
		}
		int left = remaining.decrementAndGet();
		if (left <= 0) {
			// Sınır doldu -> normale dön
			this.mode = Mode.NONE;
		}
		return true;
	}
}
