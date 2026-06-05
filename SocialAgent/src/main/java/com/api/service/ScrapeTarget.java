package com.api.service;

import java.util.UUID;

/**
 * Worker'ın bir job için çekeceği tek bir hedef hesap (CLAUDE.md Bölüm 10).
 * Hedef tipi, social_post'a hangi kolonların yazılacağını ve tekrar-analiz
 * korumasının hangi kimlikle sorgulanacağını belirler.
 *
 * @param type                       hedef tipi (OWN / MONITORED / SECTOR)
 * @param platform                   hesap platformu (şu an daima "INSTAGRAM")
 * @param accountName                hesap kullanıcı adı (Apify'a verilecek)
 * @param monitoredAccountId         MONITORED hedefte rakip hesabın id'si; aksi halde null
 * @param selectedUserSocialAccountId OWN hedefte kendi hesabın id'si; aksi halde null
 */
public record ScrapeTarget(
		TargetType type,
		String platform,
		String accountName,
		UUID monitoredAccountId,
		UUID selectedUserSocialAccountId) {

	/**
	 * Hedef hesap tipi. Tekrar-analiz koruması ve social_post kolon eşlemesi buna göre yapılır.
	 */
	public enum TargetType {
		// Kullanıcının kendi (tek) hesabı
		OWN,
		// İzlenen rakip hesap (monitored_account)
		MONITORED,
		// Sektör top-5 hesabı (Apify keyword araması ile bulundu — D1)
		SECTOR
	}

	/**
	 * OWN tipi hedef üretir.
	 */
	public static ScrapeTarget own(String platform, String accountName, UUID selectedUserSocialAccountId) {
		return new ScrapeTarget(TargetType.OWN, platform, accountName, null, selectedUserSocialAccountId);
	}

	/**
	 * MONITORED tipi hedef üretir.
	 */
	public static ScrapeTarget monitored(String platform, String accountName, UUID monitoredAccountId) {
		return new ScrapeTarget(TargetType.MONITORED, platform, accountName, monitoredAccountId, null);
	}

	/**
	 * SECTOR tipi hedef üretir.
	 */
	public static ScrapeTarget sector(String platform, String accountName) {
		return new ScrapeTarget(TargetType.SECTOR, platform, accountName, null, null);
	}
}
