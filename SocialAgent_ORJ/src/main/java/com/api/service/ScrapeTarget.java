package com.api.service;

import java.util.UUID;

/**
 * Worker'ın bir job için çekeceği tek bir hedef (CLAUDE.md Bölüm 10 — WorkerPrompt revizyonu).
 * Hedef tipi, social_post'a hangi kolonların yazılacağını ve tekrar-analiz
 * korumasının hangi kimlikle sorgulanacağını belirler.
 *
 * @param type                        hedef tipi (OWN / MONITORED / SECTOR)
 * @param platform                    hesap platformu (şu an daima "INSTAGRAM")
 * @param accountName                 hesap kullanıcı adı (kayıt/log için; SECTOR'da null olabilir)
 * @param url                         Apify directUrls'e verilecek Instagram URL'i
 * @param monitoredAccountId          MONITORED hedefte rakip hesabın id'si; aksi halde null
 * @param selectedUserSocialAccountId OWN hedefte kendi hesabın id'si; aksi halde null
 */
public record ScrapeTarget(
		TargetType type,
		String platform,
		String accountName,
		String url,
		UUID monitoredAccountId,
		UUID selectedUserSocialAccountId) {

	private static final String INSTAGRAM_BASE = "https://www.instagram.com/";

	/**
	 * Hedef hesap tipi. Tekrar-analiz koruması ve social_post kolon eşlemesi buna göre yapılır.
	 */
	public enum TargetType {
		// Kullanıcının kendi (tek) hesabı
		OWN,
		// İzlenen rakip hesap (monitored_account)
		MONITORED,
		// Sektör hashtag explore sayfası (OpenAI hashtag araması — WorkerPrompt a-maddesi)
		SECTOR
	}

	/**
	 * OWN tipi hedef üretir. URL: https://www.instagram.com/{accountName}/
	 */
	public static ScrapeTarget own(String platform, String accountName, UUID selectedUserSocialAccountId) {
		String url = INSTAGRAM_BASE + accountName + "/";
		return new ScrapeTarget(TargetType.OWN, platform, accountName, url, null, selectedUserSocialAccountId);
	}

	/**
	 * MONITORED tipi hedef üretir. URL: https://www.instagram.com/{accountName}/
	 */
	public static ScrapeTarget monitored(String platform, String accountName, UUID monitoredAccountId) {
		String url = INSTAGRAM_BASE + accountName + "/";
		return new ScrapeTarget(TargetType.MONITORED, platform, accountName, url, monitoredAccountId, null);
	}

	/**
	 * SECTOR tipi hedef üretir. URL: hashtag explore sayfası (ör. .../explore/tags/makyaj/).
	 * accountName null olabilir (hashtag URL'inden bilinmez; Apify yanıtındaki ownerUsername kullanılır).
	 */
	public static ScrapeTarget sector(String platform, String exploreUrl) {
		return new ScrapeTarget(TargetType.SECTOR, platform, null, exploreUrl, null, null);
	}
}
