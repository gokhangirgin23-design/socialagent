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
		paymentService = mock(PaymentService.class);
		appProperties = new AppProperties();
		// Bu testlerin odağı Brand DNA / görsel kalite / visual pattern akışı; ödeme akışı devre dışı
		appProperties.getPayment().setEnabled(false);
		service = new ContentPipelineService(contentRequestRepository, jdbcTemplate, aiAnalysisService,
				openAiImageService, geminiImageService, veoVideoService, s3UploadService,
				paymentService, appProperties);
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
				"visualThemes":["sokak lezzeti","ahşap"],
				"sceneDescription":"Ahşap masada dumanı tüten Adana kebap, arka planda mangal ateşi.",
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

		Method loadVisualPatterns = ContentPipelineService.class.getDeclaredMethod("loadVisualPatterns", UUID.class, String.class);
		loadVisualPatterns.setAccessible(true);
		String result = (String) loadVisualPatterns.invoke(service, reportId, null);

		assertNotNull(result);
		assertTrue(result.contains("[KENDİ]"));
		assertTrue(result.contains("[RAKİP]"));
		assertTrue(result.contains("Renkler=kırmızı, turuncu, bej"));
		assertTrue(result.contains("Dekor=tahta kesme tahtası"));
		assertTrue(result.contains("Temalar=sokak lezzeti, ahşap"));
		assertTrue(result.contains("Sahne=Ahşap masada dumanı tüten Adana kebap, arka planda mangal ateşi."));
	}

	@Test
	void gorselDesenSorgusuKendiPostlariOnceliklendirir() throws Exception {
		// Bir raporda rakip/sektör postu sayısı KENDİ'den kat kat fazla olduğunda, KENDİ
		// postlarının salt tarih sıralamasında LIMIT'in dışına düşmemesi gerekir — DNA'nın
		// ana kimlik kaynağı KENDİ'dir (sınıf yorumu). Gerçek oturumda bulunan bir örnek:
		// 5 KENDİ postuna karşı 25 SEKTÖR postu vardı; eski "ORDER BY post_date DESC LIMIT 15"
		// sorgusu KENDİ'nin yalnızca 2'sini (en yenilerini) alıyor, en ayırt edici olanı
		// (rakiplerin arasına gömülü, daha eski bir KENDİ postu) hiç görmüyordu.
		Method loadVisualPatterns = ContentPipelineService.class.getDeclaredMethod("loadVisualPatterns", UUID.class, String.class);
		loadVisualPatterns.setAccessible(true);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		when(jdbcTemplate.query(sqlCaptor.capture(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), (Object[]) any()))
				.thenReturn(List.of());

		loadVisualPatterns.invoke(service, UUID.randomUUID(), null);

		String executedSql = sqlCaptor.getValue();
		assertTrue(executedSql.contains("source_type = 'OWN'"),
				"Sorgu KENDİ postlarını önceliklendirmeli (ORDER BY (source_type = 'OWN') DESC, ...)");
	}

	@SuppressWarnings("unchecked")
	@Test
	void alakasizSektorHesabiBrandDnaVerisindenDislanir() throws Exception {
		// Moda/Lüks Moda vakası: Apify "Lüks Moda" aramasında 2 gerçek moda hesabı + 1 alakasız
		// emlak hesabı buluyor (kullanıcı adında "moda"/"luks" geçtiği için yanlış eşleşme) —
		// bkz. SectorRelevanceFilter. Emlak hesabının verisi Brand DNA girdisine karışmamalı.
		UUID reportId = UUID.randomUUID();

		ArgumentCaptor<RowMapper<Object>> relevanceMapper = ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(contains("sp.sector_account_name, pa.analysis_json"), relevanceMapper.capture(), (Object[]) any()))
				.thenAnswer(invocation -> {
					RowMapper<Object> mapper = relevanceMapper.getValue();
					return List.of(
							mapper.mapRow(mockRelevanceRow("moda_hesap_1", "{\"visual\":{\"productCategory\":\"kadın giyim\"}}"), 0),
							mapper.mapRow(mockRelevanceRow("moda_hesap_2", "{\"visual\":{\"productCategory\":\"erkek giyim\"}}"), 1),
							mapper.mapRow(mockRelevanceRow("emlak_hesap", "{\"visual\":{\"productCategory\":\"gayrimenkul\"}}"), 2));
				});

		ArgumentCaptor<RowMapper<Object>> visualMapper = ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(contains("pa.analysis_json, sp.source_type"), visualMapper.capture(), (Object[]) any()))
				.thenAnswer(invocation -> {
					RowMapper<Object> mapper = visualMapper.getValue();
					return List.of(
							mapper.mapRow(mockVisualRow3("{\"visual\":{\"productCategory\":\"kadın giyim\",\"atmosphere\":\"şık\"}}", "SECTOR", "moda_hesap_1"), 0),
							mapper.mapRow(mockVisualRow3("{\"visual\":{\"productCategory\":\"erkek giyim\",\"atmosphere\":\"şık\"}}", "SECTOR", "moda_hesap_2"), 1),
							mapper.mapRow(mockVisualRow3("{\"visual\":{\"productCategory\":\"gayrimenkul\",\"atmosphere\":\"lüks\"}}", "SECTOR", "emlak_hesap"), 2));
				});

		Method loadVisualPatterns = ContentPipelineService.class.getDeclaredMethod("loadVisualPatterns", UUID.class, String.class);
		loadVisualPatterns.setAccessible(true);
		String result = (String) loadVisualPatterns.invoke(service, reportId, null);

		assertNotNull(result);
		assertTrue(result.contains("kadın giyim"), "İlgili sektör hesabı verisi kalmalı: " + result);
		assertTrue(result.contains("erkek giyim"), "İlgili sektör hesabı verisi kalmalı: " + result);
		assertTrue(!result.contains("gayrimenkul"), "Alakasız hesap verisi dışlanmalı: " + result);
	}

	private ResultSet mockRelevanceRow(String accountName, String analysisJson) throws SQLException {
		ResultSet rs = mock(ResultSet.class);
		when(rs.getString("sector_account_name")).thenReturn(accountName);
		when(rs.getString("analysis_json")).thenReturn(analysisJson);
		return rs;
	}

	private ResultSet mockVisualRow3(String analysisJson, String sourceType, String sectorAccountName) throws SQLException {
		ResultSet rs = mock(ResultSet.class);
		when(rs.getString("analysis_json")).thenReturn(analysisJson);
		when(rs.getString("source_type")).thenReturn(sourceType);
		when(rs.getString("sector_account_name")).thenReturn(sectorAccountName);
		return rs;
	}

	@Test
	void editIleTekrarUretimdeKrediZatenDusulmusseTekrarDusulmez() throws Exception {
		appProperties.getPayment().setEnabled(true);

		ContentRequest req = sampleRequest();
		req.setCreditDebited((short) 1); // ilk üretimde zaten başarıyla düşülmüş

		Method shouldDebit = ContentPipelineService.class.getDeclaredMethod("shouldDebitOnCompletion", ContentRequest.class);
		shouldDebit.setAccessible(true);

		assertEquals(false, shouldDebit.invoke(service, req));
	}

	@Test
	void ilkUretimdeKrediHenuzDusulmemisseDusulur() throws Exception {
		appProperties.getPayment().setEnabled(true);

		ContentRequest req = sampleRequest();
		req.setCreditDebited((short) 0);

		Method shouldDebit = ContentPipelineService.class.getDeclaredMethod("shouldDebitOnCompletion", ContentRequest.class);
		shouldDebit.setAccessible(true);

		assertEquals(true, shouldDebit.invoke(service, req));
	}

	@Test
	void odemeKapaliysaHicDusulmez() throws Exception {
		appProperties.getPayment().setEnabled(false);

		ContentRequest req = sampleRequest();
		req.setCreditDebited((short) 0);

		Method shouldDebit = ContentPipelineService.class.getDeclaredMethod("shouldDebitOnCompletion", ContentRequest.class);
		shouldDebit.setAccessible(true);

		assertEquals(false, shouldDebit.invoke(service, req));
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
