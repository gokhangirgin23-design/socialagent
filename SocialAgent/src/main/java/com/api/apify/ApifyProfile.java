package com.api.apify;

/**
 * Apify keyword/profil aramasından dönen tek bir hesap (D1 — CLAUDE.md Bölüm 10).
 * Sektör "top 5" belirlenirken followerCount + engagementRate'e göre sıralanır.
 *
 * @param accountName    hesap kullanıcı adı (Instagram username)
 * @param profileUrl     profil URL'i (varsa)
 * @param followerCount  takipçi sayısı (sıralama birincil kriteri)
 * @param engagementRate etkileşim oranı (eşitlik bozucu ikincil kriter)
 * @param fullName       profil görünen adı (SORUN 1, madde 1.2 — alt sektör relevance skorlaması için; yoksa null)
 * @param biography      profil biyografisi (SORUN 1, madde 1.2 — alt sektör relevance skorlaması için; yoksa null)
 */
public record ApifyProfile(
		String accountName,
		String profileUrl,
		long followerCount,
		double engagementRate,
		String fullName,
		String biography) {
}
