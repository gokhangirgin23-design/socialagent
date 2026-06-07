package com.api.security;

/**
 * Google id_token doğrulandıktan sonra elde edilen kullanıcı bilgisi.
 *
 * @param googleId        Google subject (benzersiz hesap id'si)
 * @param email           kullanıcı e-postası
 * @param fullName        tam ad (name claim)
 * @param profilePhotoUrl profil fotoğrafı (picture claim)
 */
public record GoogleUserData(
		String googleId,
		String email,
		String fullName,
		String profilePhotoUrl) {
}
