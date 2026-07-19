package com.api.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hesap bazlı Brand DNA cache (user_account_dna) invalidation'ı için ortak yardımcı.
 * AccountService (hesap adı değişimi/silme) ve SectorService (sektör/alt sektör değişimi)
 * tarafından çağrılır; ContentPipelineService.resolveAccountDna bir sonraki içerik üretiminde
 * pasife alınan kaydı görüp DNA'yı yeniden üretir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDnaCacheService {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * Kullanıcının aktif hesap DNA cache kaydını pasife alır (varsa).
	 */
	public void invalidateAccountDnaCache(UUID userId) {
		int updated = jdbcTemplate.update("""
				UPDATE user_account_dna SET active = 0, updated_date = ?
				WHERE user_id = ? AND active = 1
				""", LocalDateTime.now(), userId);
		log.info("Hesap DNA cache pasife alındı: userId={}, pasifeAlınanSatır={}", userId, updated);
	}
}
