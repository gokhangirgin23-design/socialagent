package com.api.local;

import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.ai.AiAnalysisService;
import com.api.service.ReportPipelineService;
import com.api.service.ReportService;

import lombok.extern.slf4j.Slf4j;

/**
 * LOCAL profil için ReportPipelineService yerine geçen dummy sarmalayıcı (sadece iç test).
 * Gerçek ReportPipelineService'ten TÜRER; generateReport'u override eder.
 *
 * SORUN: Gerçek generateReport, hesap özetleri (social_post ⋈ post_analysis) boş gelirse
 *        ensureReport'a hiç ulaşmadan "return false" eder; bu durumda report kaydı oluşmaz
 *        ya da mevcut kayda report_content YAZILMAZ (boş kalır). Local dummy akışta bu join
 *        bazı durumlarda boş kaldığı için raporun içeriği boş görünüyordu.
 *
 * ÇÖZÜM (yalnızca local): Önce gerçek akış denenir (super.generateReport). Özet doluysa
 *        rapor normal şekilde üretilir ve bu sınıf hiçbir şey yapmaz. Özet boşsa (super false
 *        döndüyse), local'de yine de garantili bir dummy Markdown rapor yazılır.
 *
 * @Primary + @Profile("local"): yalnızca local'de devreye girer; diğer ortamlarda gerçek
 * ReportPipelineService aynen çalışır (bu sınıf hiç oluşturulmaz).
 *
 * Not: Service interface yok (CLAUDE.md Madde 1) olduğundan değiştirme için kalıtım kullanıldı.
 */
@Slf4j
@Service
@Profile("local")
@Primary
public class LocalDummyReportPipelineService extends ReportPipelineService {

	// report yazma + durum geçişleri (override içinde doğrudan kullanılır)
	private final ReportService reportService;

	// Dummy yanıt havuzu (rastgele Markdown rapor)
	private final DummyResponseProvider dummy;

	/**
	 * Üst sınıfın zorunlu bağımlılıkları super'a iletilir (field sırası: jdbcTemplate,
	 * aiAnalysisService, reportService). Local'de aiAnalysisService = LocalDummyAiAnalysisService.
	 */
	public LocalDummyReportPipelineService(JdbcTemplate jdbcTemplate,
			AiAnalysisService aiAnalysisService,
			ReportService reportService,
			DummyResponseProvider dummy) {
		super(jdbcTemplate, aiAnalysisService, reportService);
		this.reportService = reportService;
		this.dummy = dummy;
	}

	/**
	 * Önce gerçek akışı dener; özet boş olduğu için rapor yazılmadıysa local'de dummy rapor yazar.
	 */
	@Override
	@Transactional
	public boolean generateReport(UUID requestId) {
		// 1) Normal akış: özet doluysa gerçek (dummy AI'lı) rapor üretilir
		if (super.generateReport(requestId)) {
			return true;
		}

		// 2) Özet boştu -> local iç test için garantili dummy rapor yaz
		UUID reportId = reportService.ensureReport(requestId);
		reportService.markGenerating(reportId);
		reportService.markCompleted(reportId, dummy.randomReportMarkdown());
		log.info("[LOCAL-DUMMY] Özet boş -> dummy rapor yazıldı: requestId={}, reportId={}",
				requestId, reportId);
		return true;
	}
}
