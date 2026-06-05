package com.api.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.ai.AiAnalysisService;
import com.api.ai.prompt.ReportPrompts;
import com.api.dto.ReportPostRow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rapor üretim pipeline'ı (FAZ 7 — CLAUDE.md Bölüm 11).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Akış (AnalysisPipelineService deseninin rapor karşılığı):
 *   1) Job'a ait tüm post_analysis JSON'larını topla (post_analysis ⋈ social_post — native, eski stil "=").
 *   2) Analiz yoksa rapor üretme (boş tur).
 *   3) report kaydını garanti et (yoksa PENDING oluştur) -> GENERATING.
 *   4) ReportPrompts.forJob ile prompt üret -> AiAnalysisService.generateReport (OpenAI -> Markdown).
 *   5) Sonuç doluysa COMPLETED + içerik; boş/başarısızsa FAILED.
 *
 * Worker scraping + analiz bittikten sonra bu pipeline'ı çağırır (ScrapePipelineService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportPipelineService {

	// Analizleri toplamak için JdbcTemplate native join
	private final JdbcTemplate jdbcTemplate;

	// OpenAI ile Markdown rapor üretimi (model wiring tek sınıfta — FAZ 6 kararı)
	private final AiAnalysisService aiAnalysisService;

	// report yazma + durum geçişleri
	private final ReportService reportService;

	/**
	 * Bir job'ın tüm analizlerinden tek Markdown rapor üretir ve report'a yazar.
	 *
	 * @param userJobId raporlanacak job
	 * @return rapor COMPLETED olduysa true; analiz yoksa veya üretim başarısızsa false
	 */
	@Transactional
	public boolean generateReport(UUID userJobId) {
		// 1) Job'a ait analizleri topla
		List<ReportPostRow> rows = loadAnalyses(userJobId);
		if (rows.isEmpty()) {
			// Henüz analiz yok -> rapor üretilmez (boş tur; idempotent)
			log.info("Rapor için analiz bulunamadı, rapor üretilmedi: userJobId={}", userJobId);
			return false;
		}

		// 2) report kaydını garanti et (yoksa PENDING) ve GENERATING'e geçir
		UUID reportId = reportService.ensureReport(userJobId);
		reportService.markGenerating(reportId);

		// 3) Prompt üret ve OpenAI'dan Markdown iste
		String prompt = ReportPrompts.forJob(rows);
		String markdown = aiAnalysisService.generateReport(prompt);

		// 4) Sonuca göre durum geçişi
		if (markdown == null || markdown.isBlank()) {
			// AI yok / hata / boş çıktı -> FAILED (uygulama çökmez)
			reportService.markFailed(reportId);
			log.warn("Rapor üretilemedi (AI yok/boş), FAILED: userJobId={}, reportId={}", userJobId, reportId);
			return false;
		}

		// Başarılı -> COMPLETED + Markdown içerik
		reportService.markCompleted(reportId, markdown);
		log.info("Rapor üretildi (COMPLETED): userJobId={}, reportId={}, analizSayisi={}",
				userJobId, reportId, rows.size());
		return true;
	}

	/**
	 * Job'a ait tüm analizleri (post_analysis) ilgili gönderiyle (social_post) birlikte çeker.
	 * İlişkili tablolar native query + eski stil "=" ile birleştirilir (CLAUDE.md Madde 6).
	 * Kaynak etiketi (KENDİ/RAKİP/SEKTÖR) social_post kolonlarından türetilir (SocialPost dokümantasyonu).
	 */
	private List<ReportPostRow> loadAnalyses(UUID userJobId) {
		// post_analysis ⋈ social_post (inner join, eski stil "=")
		String sql = """
				SELECT sp.monitored_account_id, sp.account_name_sector,
				       sp.media_type, sp.caption, sp.hashtags,
				       sp.likes_count, sp.comments_count, sp.views_count,
				       pa.analysis_json
				FROM post_analysis pa, social_post sp
				WHERE pa.social_post_id = sp.social_post_id
				  AND sp.user_job_id = ?
				ORDER BY sp.post_date DESC
				""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> {
			// Kaynak tipini kolonlardan türet (rapor karşılaştırması için okunaklı etiket)
			UUID monitoredId = rs.getObject("monitored_account_id", UUID.class);
			String sectorName = rs.getString("account_name_sector");
			String source;
			if (monitoredId != null) {
				// Rakip (monitored) hesap gönderisi
				source = "RAKİP";
			} else if (sectorName != null && !sectorName.isBlank()) {
				// Sektör top-5 hesabı (D1) — ad ile etiketle
				source = "SEKTÖR (" + sectorName + ")";
			} else {
				// Kullanıcının kendi (tek) hesabı
				source = "KENDİ HESABIN";
			}
			return new ReportPostRow(
					source,
					rs.getString("media_type"),
					rs.getString("caption"),
					rs.getString("hashtags"),
					rs.getObject("likes_count", Long.class),
					rs.getObject("comments_count", Long.class),
					rs.getObject("views_count", Long.class),
					rs.getString("analysis_json"));
		}, userJobId);
	}
}
