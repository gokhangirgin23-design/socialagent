package com.api.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyPost;
import com.api.config.AppProperties;
import com.api.entity.UserJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Worker'ın çalıştırdığı scraping pipeline'ı (FAZ 5 — CLAUDE.md Bölüm 9, 10).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Akış:
 *   1) user_job'ı yükle (JdbcTemplate native).
 *   2) analysis_mode'a göre hedefleri çöz (TargetResolver).
 *   3) Her hedef için tekrar-analiz koruması; gerekiyorsa Apify'dan son N gönderi çek.
 *   4) Gönderileri social_post'a yaz (dedup servis kontrolü).
 *   5) FAZ 6: analiz edilmemiş gönderileri AI ile analiz et (AnalysisPipelineService -> post_analysis).
 *   6) FAZ 7: analizlerden Markdown rapor üret (ReportPipelineService -> report).
 *   7) FAZ 7: iş sonu muhasebesi (JobCompletionService — current_count++, completed, queued reset,
 *      periyot bazlı yeniden zamanlama). Bu adım finally bloğunda; hata olsa da job takılı kalmaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScrapePipelineService {

	// Native job yükleme için JdbcTemplate
	private final JdbcTemplate jdbcTemplate;

	// Mod -> hedef hesap kümesi çözümleyici
	private final TargetResolver targetResolver;

	// Apify gönderi çekme istemcisi
	private final ApifyClient apifyClient;

	// social_post yazma + tekrar-analiz koruması
	private final SocialPostService socialPostService;

	// FAZ 6: scraping sonrası AI analizini çalıştıran pipeline
	private final AnalysisPipelineService analysisPipelineService;

	// FAZ 7: analizlerden Markdown rapor üreten pipeline
	private final ReportPipelineService reportPipelineService;

	// FAZ 8: rapor tamamlanınca bildirim (DB + mail + push)
	private final NotificationService notificationService;

	// FAZ 7: iş sonu muhasebesi (current_count++, completed, queued reset, yeniden zamanlama)
	private final JobCompletionService jobCompletionService;

	// Çekilecek son gönderi sayısı gibi ayarlar
	private final AppProperties appProperties;

	/**
	 * Tek bir job'ı baştan sona işler (worker bunu çağırır).
	 * @Transactional YOK: Apify (~120s) ve AI (~60s) HTTP çağrıları burada tetikleniyor.
	 * Transaction tutmak DB connection pool'u tüketir. Alt servisler kendi kısa
	 * transaction'larını yönetir (saveRecentPosts, analyzeJob, generateReport).
	 * finalizeJob REQUIRES_NEW kullandığından pipeline hatası onu etkilemez.
	 *
	 * @param userJobId işlenecek job'ın id'si
	 */
	public void processJob(UUID userJobId) {
		// 1) Job'ı yükle (aktif olmalı)
		UserJob job = loadJob(userJobId);
		if (job == null) {
			// Job bulunamadı/silinmiş -> sessizce bitir (mesaj ack edilir). Muhasebe yapılmaz.
			log.warn("İşlenecek job bulunamadı veya pasif: userJobId={}", userJobId);
			return;
		}

		try {
			// 2) Moda göre hedefleri çöz
			List<ScrapeTarget> targets = targetResolver.resolve(job);
			if (targets.isEmpty()) {
				// Hedef yoksa scraping atlanır; analiz/rapor yine de denenir (mevcut veriyle no-op olabilir)
				log.info("Job için hedef hesap çıkmadı: userJobId={}, mode={}", userJobId, job.getAnalysisMode());
			} else {
				// analysis_period_days null ise varsayılan 7 (tekrar-analiz penceresi)
				int periodDays = (job.getAnalysisPeriodDays() != null) ? job.getAnalysisPeriodDays() : 7;
				// Çekilecek son gönderi sayısı (config; D1 varsayılan 5)
				int recentLimit = appProperties.getApify().getRecentPostsLimit();

				int totalInserted = 0;
				// 3) Her hedef için pipeline
				for (ScrapeTarget target : targets) {
					// Tekrar-analiz koruması: son pencerede analiz edildiyse Apify'a gitme
					if (socialPostService.isRecentlyAnalyzed(target, periodDays)) {
						continue;
					}
					// Apify'dan son N gönderiyi çek
					List<ApifyPost> posts = apifyClient.fetchRecentPosts(target.accountName(), recentLimit);
					// social_post'a yaz (dedup'lı); eklenen sayıyı topla
					totalInserted += socialPostService.saveRecentPosts(userJobId, target, posts);
				}

				log.info("Job scraping tamamlandı: userJobId={}, hedef={}, toplamEklenen={}",
						userJobId, targets.size(), totalInserted);
			}

			// 4) FAZ 6: scraping biter bitmez analiz edilmemiş gönderileri AI ile analiz et.
			//    (media_type'a göre OpenAI/Gemini yönlendirmesi -> post_analysis). Idempotent.
			int analyzed = analysisPipelineService.analyzeJob(userJobId);
			log.info("Job AI analizi tamamlandı: userJobId={}, yazılanAnaliz={}", userJobId, analyzed);

			// 5) FAZ 7: tüm post_analysis JSON'larını topla -> OpenAI -> Markdown rapor (report).
			boolean reportDone = reportPipelineService.generateReport(userJobId);
			log.info("Job rapor üretimi: userJobId={}, raporTamamlandi={}", userJobId, reportDone);

			// 6) FAZ 8: rapor COMPLETED ise kullanıcıya bildirim (notification kaydı + mail + push).
			//    Bildirim adımı bağımsız tx'tir (REQUIRES_NEW) ve ek olarak try/catch ile sarmalanır;
			//    olası bir bildirim hatası rapor yazımını/iş sonu muhasebesini BOZMAZ.
			if (reportDone) {
				try {
					notificationService.notifyReportCompleted(userJobId);
				} catch (Exception ex) {
					log.warn("Bildirim gönderilemedi (pipeline etkilenmedi): userJobId={}, hata={}",
							userJobId, ex.getMessage());
				}
			}
		} finally {
			// 7) FAZ 7: iş sonu muhasebesi — current_count++; gerekiyorsa completed=1; queued reset;
			//    devam eden recurring job için periyot bazlı next_run_date. Hata olsa da çalışmalı
			//    ki job "queued=1" durumunda takılı kalmasın (finally).
			jobCompletionService.finalizeJob(userJobId);
		}
	}

	/**
	 * user_job'ı id ile yükler (yalnızca pipeline'ın ihtiyaç duyduğu kolonlar).
	 * Bulunamazsa/pasifse null.
	 */
	private UserJob loadJob(UUID userJobId) {
		String sql = """
				SELECT user_job_id, user_id, selected_user_social_account_id,
				       analysis_mode, analysis_period_days
				FROM user_job
				WHERE user_job_id = ? AND active = 1
				""";
		List<UserJob> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
			UserJob j = new UserJob();
			j.setUserJobId(rs.getObject("user_job_id", UUID.class));
			j.setUserId(rs.getObject("user_id", UUID.class));
			j.setSelectedUserSocialAccountId(rs.getObject("selected_user_social_account_id", UUID.class));
			j.setAnalysisMode(rs.getString("analysis_mode"));
			j.setAnalysisPeriodDays(rs.getObject("analysis_period_days", Integer.class));
			return j;
		}, userJobId);
		return rows.isEmpty() ? null : rows.get(0);
	}
}
