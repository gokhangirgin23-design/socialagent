package com.api.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.repository.PostAnalysisRepository;
import com.api.entity.PostAnalysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * post_analysis yazma + "zaten analiz edilmiş mi" kontrolü (FAZ 6 — CLAUDE.md Bölüm 11). Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Her social_post için en fazla bir analiz tutulur; insert öncesi varlık servis katmanında elle kontrol edilir (CLAUDE.md Madde 5 mantığı — unique gibi davranır). Lookup JdbcTemplate native; insert
 * JPA save.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostAnalysisService {

	// Native dedup sorgusu için JdbcTemplate
	private final JdbcTemplate jdbcTemplate;

	// post_analysis insert için JPA repository
	private final PostAnalysisRepository postAnalysisRepository;

	/**
	 * Bu social_post için zaten bir analiz kaydı var mı? (idempotency). Pipeline tekrar çalışırsa aynı postu yeniden analiz etmemek için kullanılır.
	 *
	 * @return analiz kaydı varsa true
	 */
	@Transactional(readOnly = true)
	public boolean isAlreadyAnalyzed(UUID socialPostId) {
		// social_post_id ile mevcut analiz id'lerini ara
		String sql = """
				SELECT post_analysis_id
				FROM post_analysis
				WHERE social_post_id = ?
				""";
		List<UUID> rows = jdbcTemplate.query(sql,
				(rs, rowNum) -> rs.getObject("post_analysis_id", UUID.class),
				socialPostId);
		return !rows.isEmpty();
	}

	/**
	 * Bir gönderinin analiz JSON'ını post_analysis'e yazar. Yazmadan önce dedup kontrolü yapar; zaten analiz varsa yeniden yazmaz. analysisJson null/blank ise (AI atlandı/başarısız) kayıt
	 * oluşturulmaz.
	 *
	 * @return yeni kayıt oluşturulduysa true
	 */
	@Transactional
	public boolean saveAnalysis(UUID socialPostId, String analysisJson) {
		// AI sonuç üretmediyse (key yok / hata) kaydetme
		if (analysisJson == null || analysisJson.isBlank()) {
			log.debug("Analiz JSON'ı boş; post_analysis yazılmadı (postId={}).", socialPostId);
			return false;
		}
		// Zaten analiz varsa tekrar yazma (servis seviyesi unique kontrolü)
		if (isAlreadyAnalyzed(socialPostId)) {
			log.debug("Gönderi zaten analiz edilmiş, atlanıyor (postId={}).", socialPostId);
			return false;
		}

		// Yeni analiz kaydı oluştur
		LocalDateTime now = LocalDateTime.now();
		PostAnalysis pa = new PostAnalysis();
		pa.setPostAnalysisId(UUID.randomUUID());
		pa.setSocialPostId(socialPostId);
		pa.setAnalysisJson(analysisJson);
		pa.setCreatedDate(now);
		pa.setUpdatedDate(now);

		// JPA saveAndFlush ile insert: INSERT'i hemen tetikler ki UNIQUE ihlali
		// burada yakalanabilsin (deferred flush'ta hata commit anına kaçardı).
		try {
			postAnalysisRepository.saveAndFlush(pa);
		} catch (DataIntegrityViolationException e) {
			// Eşzamanlı başka bir worker aynı postu yazmış (race); UNIQUE devreye
			// girdi → sessizce atla, pipeline patlamasın.
			log.debug("post_analysis UNIQUE ihlali (eşzamanlı yazım), atlanıyor (postId={}).", socialPostId);
			return false;
		}
		log.info("post_analysis yazıldı: postId={}", socialPostId);
		return true;
	}
}
