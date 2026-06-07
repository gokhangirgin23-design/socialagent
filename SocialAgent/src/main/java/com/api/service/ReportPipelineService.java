package com.api.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.ai.AiAnalysisService;
import com.api.ai.prompt.ReportPrompts;
import com.api.dto.AccountReportRow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rapor üretim pipeline'ı (FAZ 7 — WorkerPrompt revizyonu, CLAUDE.md Bölüm 11).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Veri akışı (iyileştirilmiş — token verimliliği):
 *   social_post → SQL aggregate (avg likes, içerik tipi dağılımı vb.)  ─┐
 *   post_analysis → SQL (analysis_json listesi)                          ├→ Java hesap özeti
 *   analysis_json → Java-side JSON parse (isReel, hasHuman, vb.)        ─┘
 *   → OpenAI'ya hesap başına tek satır gönderilir (150 post yerine 5-10 satır)
 *   → report.report_content (Markdown) — sadece YAZILIR, hiç okunmaz
 *
 * İki ayrı SQL sorgusu kullanılır:
 *   1) OWN + SECTOR (monitored_account_id IS NULL): tek join, account_name_sector'dan ad alınır.
 *   2) MONITORED: monitored_account ile eski stil "=" join, account_name alınır.
 * Her iki sonuç Java'da birleştirilip post başına analysis_json'dan görsel metrikler sayılır.
 *
 * report.report_content INPUT DEĞİL, OUTPUT'tur — hiçbir zaman okunmaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportPipelineService {

	// Sorgular için JdbcTemplate native
	private final JdbcTemplate jdbcTemplate;

	// OpenAI ile Markdown rapor üretimi
	private final AiAnalysisService aiAnalysisService;

	// report yazma + durum geçişleri
	private final ReportService reportService;

	// analysis_json parse için (thread-safe)
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Bir job'ın hesap bazlı özetlerinden Markdown rapor üretir ve report'a yazar.
	 *
	 * @param userJobId raporlanacak job
	 * @return rapor COMPLETED olduysa true; özet yoksa veya üretim başarısızsa false
	 */
	@Transactional
	public boolean generateReport(UUID userJobId) {
		// 1) Job'ın analysisMode'unu yükle (karşılaştırmalı mı, başarı faktörü mü)
		String analysisMode = loadAnalysisMode(userJobId);

		// 2) Hesap bazlı özetleri topla (SQL aggregate + Java JSON parse)
		List<AccountReportRow> summaries = loadAccountSummaries(userJobId);
		if (summaries.isEmpty()) {
			log.info("Rapor için özet bulunamadı, rapor üretilmedi: userJobId={}", userJobId);
			return false;
		}

		// 3) report kaydını garanti et ve GENERATING'e geçir
		UUID reportId = reportService.ensureReport(userJobId);
		reportService.markGenerating(reportId);

		// 4) Prompt üret ve OpenAI'dan Markdown iste
		String prompt = ReportPrompts.forJob(summaries, analysisMode);
		String markdown = aiAnalysisService.generateReport(prompt);

		// 5) Sonuca göre durum geçişi
		if (markdown == null || markdown.isBlank()) {
			reportService.markFailed(reportId);
			log.warn("Rapor üretilemedi (AI yok/boş), FAILED: userJobId={}, reportId={}", userJobId, reportId);
			return false;
		}

		reportService.markCompleted(reportId, markdown);
		log.info("Rapor üretildi (COMPLETED): userJobId={}, reportId={}, hesapSayisi={}",
				userJobId, reportId, summaries.size());
		return true;
	}

	// ============================================================
	// Veri yükleme
	// ============================================================

	/**
	 * Job'ın analysisMode değerini yükler; bulunamazsa "NONE" döner.
	 */
	private String loadAnalysisMode(UUID userJobId) {
		String sql = "SELECT analysis_mode FROM user_job WHERE user_job_id = ?";
		List<String> modes = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("analysis_mode"), userJobId);
		return modes.isEmpty() ? "NONE" : modes.get(0);
	}

	/**
	 * Hesap bazlı özetleri iki SQL sorgusu + Java aggregate ile üretir.
	 *
	 * Sorgular ayrı çalıştırılır:
	 *   - OWN + SECTOR: monitored_account_id IS NULL, account_name_sector'dan hesap adı
	 *   - MONITORED:    monitored_account eski stil "=" join ile account_name
	 *
	 * Her iki sonuç Java'da birleştirilir; analysis_json'dan görsel metrikler sayılır.
	 */
	private List<AccountReportRow> loadAccountSummaries(UUID userJobId) {
		// Ham post satırları (post + analiz birlikte)
		List<PostRaw> rawRows = new ArrayList<>();
		rawRows.addAll(loadOwnAndSectorPosts(userJobId));
		rawRows.addAll(loadMonitoredPosts(userJobId));

		if (rawRows.isEmpty()) {
			return List.of();
		}

		// Hesap bazında grupla: "TİP:hesap_adi" → post listesi
		Map<String, List<PostRaw>> byAccount = new LinkedHashMap<>();
		for (PostRaw row : rawRows) {
			String key = row.source() + ":" + row.accountName();
			byAccount.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
		}

		// Her hesap grubunu tek özet satıra dönüştür
		List<AccountReportRow> summaries = new ArrayList<>();
		for (List<PostRaw> posts : byAccount.values()) {
			summaries.add(aggregate(posts));
		}
		return summaries;
	}

	/**
	 * OWN + SECTOR hesapların post + analiz verilerini çeker (monitored_account_id IS NULL).
	 * İki tablo eski stil "=" join (CLAUDE.md Madde 6).
	 */
	private List<PostRaw> loadOwnAndSectorPosts(UUID userJobId) {
		// OWN: monitored_account_id NULL, account_name_sector NULL  → kaynak = KENDİ HESABIN
		// SECTOR: monitored_account_id NULL, account_name_sector dolu → kaynak = SEKTÖR
		String sql = """
				SELECT
				    CASE WHEN sp.account_name_sector IS NOT NULL THEN 'SEKTÖR'
				         ELSE 'KENDİ HESABIN'
				    END AS kaynak,
				    COALESCE(sp.account_name_sector, 'kendi_hesap') AS hesap_adi,
				    sp.media_type,
				    sp.likes_count,
				    sp.comments_count,
				    sp.views_count,
				    pa.analysis_json
				FROM social_post sp, post_analysis pa
				WHERE sp.social_post_id = pa.social_post_id
				  AND sp.user_job_id = ?
				  AND sp.monitored_account_id IS NULL
				ORDER BY hesap_adi
				""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new PostRaw(
				rs.getString("kaynak"),
				rs.getString("hesap_adi"),
				rs.getString("media_type"),
				rs.getObject("likes_count", Long.class),
				rs.getObject("comments_count", Long.class),
				rs.getObject("views_count", Long.class),
				rs.getString("analysis_json")), userJobId);
	}

	/**
	 * MONITORED (rakip) hesapların post + analiz verilerini çeker.
	 * monitored_account ile eski stil "=" join — account_name alınır (CLAUDE.md Madde 6).
	 */
	private List<PostRaw> loadMonitoredPosts(UUID userJobId) {
		String sql = """
				SELECT
				    'RAKİP' AS kaynak,
				    ma.account_name AS hesap_adi,
				    sp.media_type,
				    sp.likes_count,
				    sp.comments_count,
				    sp.views_count,
				    pa.analysis_json
				FROM social_post sp, post_analysis pa, monitored_account ma
				WHERE sp.social_post_id = pa.social_post_id
				  AND sp.user_job_id = ?
				  AND sp.monitored_account_id = ma.monitored_account_id
				ORDER BY ma.account_name
				""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new PostRaw(
				rs.getString("kaynak"),
				rs.getString("hesap_adi"),
				rs.getString("media_type"),
				rs.getObject("likes_count", Long.class),
				rs.getObject("comments_count", Long.class),
				rs.getObject("views_count", Long.class),
				rs.getString("analysis_json")), userJobId);
	}

	// ============================================================
	// Java-side aggregate
	// ============================================================

	/**
	 * Bir hesabın tüm post satırlarını tek AccountReportRow özetine dönüştürür.
	 * Sayısal metrikler SQL'den, görsel metrikler (isReel, hasHuman vb.) analysis_json parse'ından gelir.
	 */
	private AccountReportRow aggregate(List<PostRaw> posts) {
		String source = posts.get(0).source();
		String accountName = posts.get(0).accountName();
		long postCount = posts.size();

		// Sayısal ortalamalar
		long sumLikes = 0, sumComments = 0, sumViews = 0;
		long imageCount = 0, videoCount = 0, carouselCount = 0;
		int reelCount = 0, humanCount = 0, modelCount = 0, productFocusedCount = 0;

		for (PostRaw p : posts) {
			sumLikes += nz(p.likesCount());
			sumComments += nz(p.commentsCount());
			sumViews += nz(p.viewsCount());

			// İçerik tipi dağılımı (media_type kolonundan)
			if ("IMAGE".equalsIgnoreCase(p.mediaType())) imageCount++;
			else if ("VIDEO".equalsIgnoreCase(p.mediaType())) videoCount++;
			else if ("CAROUSEL".equalsIgnoreCase(p.mediaType())) carouselCount++;

			// Görsel metrikler: analysis_json → {"metrics":{"contentType":{"isReel":...}}, "visual":{...}}
			if (p.analysisJson() != null && !p.analysisJson().isBlank()) {
				try {
					JsonNode root = MAPPER.readTree(p.analysisJson());
					// isReel: metrics.contentType.isReel
					JsonNode isReelNode = root.path("metrics").path("contentType").path("isReel");
					if (isReelNode.isBoolean() && isReelNode.asBoolean()) reelCount++;
					// Görsel alanlar: visual.*
					JsonNode visual = root.path("visual");
					if (boolVal(visual, "hasHuman")) humanCount++;
					if (boolVal(visual, "hasModel")) modelCount++;
					if (boolVal(visual, "isProductFocused")) productFocusedCount++;
				} catch (Exception ignored) {
					// Bozuk JSON -> bu post atlanır, pipeline durmaz
				}
			}
		}

		return new AccountReportRow(
				source, accountName, postCount,
				postCount > 0 ? sumLikes / postCount : 0,
				postCount > 0 ? sumComments / postCount : 0,
				postCount > 0 ? sumViews / postCount : 0,
				imageCount, videoCount, carouselCount,
				reelCount, humanCount, modelCount, productFocusedCount);
	}

	// ============================================================
	// Yardımcılar
	// ============================================================

	private static long nz(Long v) {
		return v != null ? v : 0L;
	}

	private static boolean boolVal(JsonNode node, String field) {
		JsonNode v = node.path(field);
		return v.isBoolean() && v.asBoolean();
	}

	/** SQL post satırı (iç kullanım) — hesap bazında gruplamak için taşıyıcı. */
	record PostRaw(
			String source,
			String accountName,
			String mediaType,
			Long likesCount,
			Long commentsCount,
			Long viewsCount,
			String analysisJson) {
	}
}
