package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.ai.AiAnalysisService;
import com.api.ai.GeminiImageService;
import com.api.ai.OpenAiImageService;
import com.api.ai.VeoVideoService;
import com.api.config.AppProperties;
import com.api.dto.repository.ContentRequestRepository;
import com.api.entity.ContentRequest;
import com.api.entity.ContentType;

/**
 * ContentPipelineService için Spring'siz birim testi (DB/AI/S3 gerektirmez).
 *
 * Doğrulanan davranışlar (cost-pipeline fix — Madde 3, 5, 6-7):
 *  - Madde 3: görselde metin isteniyorsa OpenAI'a quality=high, aksi hâlde config default'u gider.
 *  - Madde 5: aynı rapora ait başka bir content_request'in Brand DNA'sı bulunursa AI çağrısı atlanır.
 *  - Madde 6-7: loadVisualPatterns çıktısı KENDİ/RAKİP etiketi + Renkler/Dekor alanlarını içerir.
 */
class ContentPipelineServiceTest {

	private ContentRequestRepository contentRequestRepository;
	private JdbcTemplate jdbcTemplate;
	private AiAnalysisService aiAnalysisService;
	private OpenAiImageService openAiImageService;
	private GeminiImageService geminiImageService;
	private VeoVideoService veoVideoService;
	private S3UploadService s3UploadService;
	private ContentRequestService contentRequestService;
	private PaymentService paymentService;
	private AppProperties appProperties;
	private ContentPipelineService service;

	@BeforeEach
	void setUp() {
		contentRequestRepository = mock(ContentRequestRepository.class);
		jdbcTemplate = mock(JdbcTemplate.class);
		aiAnalysisService = mock(AiAnalysisService.class);
		openAiImageService = mock(OpenAiImageService.class);
		geminiImageService = mock(GeminiImageService.class);
		veoVideoService = mock(VeoVideoService.class);
		s3UploadService = mock(S3UploadService.class);
		contentRequestService = mock(ContentRequestService.class);
		paymentService = mock(PaymentService.class);
		appProperties = new AppProperties();
		// Bu testlerin odağı Brand DNA / görsel kalite / visual pattern akışı; ödeme akışı devre dışı
		appProperties.getPayment().setEnabled(false);
		service = new ContentPipelineService(contentRequestRepository, jdbcTemplate, aiAnalysisService,
				openAiImageService, geminiImageService, veoVideoService, s3UploadService,
				contentRequestService, paymentService, appProperties);
	}

	@Test
	void yaziIcerenGorseldeHighDigerindeConfigKalitesiKullanilir() throws Exception {
		appProperties.getContent().setImageQuality("medium");

		ContentRequest reqYazili = sampleRequest();
		reqYazili.setIncludeTextInVisual(true);
		ContentRequest reqYazisiz = sampleRequest();
		reqYazisiz.setIncludeTextInVisual(false);

		Method generateAndUpload = ContentPipelineService.class.getDeclaredMethod(
				"generateAndUpload", ContentRequest.class, String.class, int.class, String.class);
		generateAndUpload.setAccessible(true);
		generateAndUpload.invoke(service, reqYazili, "prompt", 0, null);
		generateAndUpload.invoke(service, reqYazisiz, "prompt", 0, null);

		ArgumentCaptor<String> qualityCaptor = ArgumentCaptor.forClass(String.class);
		verify(openAiImageService, times(2)).generateImage(anyString(), any(), anyString(), qualityCaptor.capture());
		List<String> qualities = qualityCaptor.getAllValues();
		assertEquals("high", qualities.get(0));
		assertEquals("medium", qualities.get(1));
	}

	@Test
	void ikinciIcerikIstegindeAyniRaporunDnaSiTekrarUretilmez() {
		ContentRequest req = sampleRequest();
		req.setBrandDnaJson(null);

		when(contentRequestRepository.findById(req.getContentRequestId())).thenReturn(Optional.of(req));
		when(jdbcTemplate.queryForList(contains("report_content"), eq(String.class), (Object[]) any()))
				.thenReturn(List.of("# Rapor içerik"));
		when(jdbcTemplate.queryForList(contains("brand_dna_json"), eq(String.class), (Object[]) any()))
				.thenReturn(List.of("{\"mainProductOrService\":\"kebap\"}"));

		service.process(req.getContentRequestId());

		// Cache'te bulundu -> AI çağrısı hiç yapılmamalı
		verify(aiAnalysisService, never()).generateBrandDna(anyString());
		assertEquals("{\"mainProductOrService\":\"kebap\"}", req.getBrandDnaJson());
	}

	@SuppressWarnings("unchecked")
	@Test
	void gorselDesenleriEtiketliVeRenkliOzetlenir() throws Exception {
		UUID reportId = UUID.randomUUID();
		String ownJson = """
				{"visual":{"specificProduct":"Adana kebap","productCategory":"kebap","atmosphere":"sıcak",
				"shootingStyle":"close-up","lightingStyle":"sıcak ışık","backgroundType":"ahşap",
				"colorPalette":["kırmızı","turuncu","bej"],"propsAndDecor":["tahta kesme tahtası"],
				"composition":"yakın çekim"}}
				""";
		String monitoredJson = "{\"visual\":{\"specificProduct\":\"pizza\",\"productCategory\":\"pizza\",\"atmosphere\":\"canlı\"}}";

		ArgumentCaptor<RowMapper<Object>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), mapperCaptor.capture(), (Object[]) any())).thenAnswer(invocation -> {
			RowMapper<Object> mapper = mapperCaptor.getValue();
			return List.of(
					mapper.mapRow(mockRow(ownJson, "OWN"), 0),
					mapper.mapRow(mockRow(monitoredJson, "MONITORED"), 1));
		});

		Method loadVisualPatterns = ContentPipelineService.class.getDeclaredMethod("loadVisualPatterns", UUID.class);
		loadVisualPatterns.setAccessible(true);
		String result = (String) loadVisualPatterns.invoke(service, reportId);

		assertNotNull(result);
		assertTrue(result.contains("[KENDİ]"));
		assertTrue(result.contains("[RAKİP]"));
		assertTrue(result.contains("Renkler=kırmızı, turuncu, bej"));
		assertTrue(result.contains("Dekor=tahta kesme tahtası"));
	}

	// ---- yardımcılar ----

	private ContentRequest sampleRequest() {
		ContentRequest req = new ContentRequest();
		req.setContentRequestId(UUID.randomUUID());
		req.setUserId(UUID.randomUUID());
		req.setReportId(UUID.randomUUID());
		req.setContentType(ContentType.POST);
		return req;
	}

	private ResultSet mockRow(String analysisJson, String sourceType) throws SQLException {
		ResultSet rs = mock(ResultSet.class);
		when(rs.getString("analysis_json")).thenReturn(analysisJson);
		when(rs.getString("source_type")).thenReturn(sourceType);
		return rs;
	}
}
