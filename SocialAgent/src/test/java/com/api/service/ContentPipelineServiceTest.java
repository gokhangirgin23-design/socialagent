package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.api.apify.ApifyClient;
import com.api.config.AppProperties;
import com.api.dto.repository.ContentRequestRepository;
import com.api.dto.repository.UserAccountDnaRepository;
import com.api.entity.ContentRequest;
import com.api.entity.ContentType;
import com.api.entity.UserAccountDna;

/**
 * ContentPipelineService için Spring'siz birim testi (DB/AI/S3 gerektirmez).
 *
 * Doğrulanan davranışlar (ICERIK-RAPOR-AYRISTIRMA-SPEC.md §2.5):
 *  - Hesap seçili + DNA yok -> DNA üretilir, cache'lenir, prompt'ta yer alır.
 *  - Hesap seçili + DNA var -> analiz/scrape çağrılmaz, cache kullanılır.
 *  - Hesap seçili değil -> DNA sorgusu/üretimi hiç çalışmaz.
 *  - Görsel kalite: metin isteniyorsa OpenAI'a quality=high, aksi hâlde config default'u gider.
 */
class ContentPipelineServiceTest {

	private ContentRequestRepository contentRequestRepository;
	private UserAccountDnaRepository userAccountDnaRepository;
	private JdbcTemplate jdbcTemplate;
	private AiAnalysisService aiAnalysisService;
	private OpenAiImageService openAiImageService;
	private GeminiImageService geminiImageService;
	private VeoVideoService veoVideoService;
	private S3UploadService s3UploadService;
	private PaymentService paymentService;
	private AppProperties appProperties;
	private ApifyClient apifyClient;
	private ContentPipelineService service;

	@BeforeEach
	void setUp() {
		contentRequestRepository = mock(ContentRequestRepository.class);
		userAccountDnaRepository = mock(UserAccountDnaRepository.class);
		jdbcTemplate = mock(JdbcTemplate.class);
		aiAnalysisService = mock(AiAnalysisService.class);
		openAiImageService = mock(OpenAiImageService.class);
		geminiImageService = mock(GeminiImageService.class);
		veoVideoService = mock(VeoVideoService.class);
		s3UploadService = mock(S3UploadService.class);
		paymentService = mock(PaymentService.class);
		appProperties = new AppProperties();
		apifyClient = mock(ApifyClient.class);
		// Bu testlerin odağı Brand DNA / görsel kalite akışı; ödeme akışı devre dışı
		appProperties.getPayment().setEnabled(false);
		service = new ContentPipelineService(contentRequestRepository, userAccountDnaRepository, jdbcTemplate,
				aiAnalysisService, openAiImageService, geminiImageService, veoVideoService, s3UploadService,
				paymentService, appProperties, apifyClient);
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

	@SuppressWarnings("unchecked")
	@Test
	void hesapSeciliVeDnaYokIseDbdekiPostlardanUretilirVeCachelenir() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID socialAccountId = UUID.randomUUID();

		// Cache miss
		when(jdbcTemplate.queryForList(contains("user_account_dna"), eq(String.class), any(Object[].class)))
				.thenReturn(List.of());

		// DB'de zaten scrape edilmiş postlar var
		ArgumentCaptor<RowMapper<Object>> postMapper = ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(contains("report_request"), postMapper.capture(), any(Object[].class)))
				.thenAnswer(invocation -> {
					RowMapper<Object> mapper = postMapper.getValue();
					return List.of(
							mapper.mapRow(mockPostRow("Adana kebap tabağımız", "{\"visual\":{\"productCategory\":\"kebap\",\"specificProduct\":\"Adana kebap\",\"atmosphere\":\"sıcak\"}}"), 0),
							mapper.mapRow(mockPostRow("Bugünün menüsü", null), 1));
				});

		when(aiAnalysisService.generateBrandDna(anyString())).thenReturn("{\"mainProductOrService\":\"kebap\"}");

		Method resolveAccountDna = ContentPipelineService.class.getDeclaredMethod(
				"resolveAccountDna", UUID.class, UUID.class, String.class);
		resolveAccountDna.setAccessible(true);
		String dna = (String) resolveAccountDna.invoke(service, userId, socialAccountId, null);

		assertEquals("{\"mainProductOrService\":\"kebap\"}", dna);
		// Apify'a hiç gidilmedi (DB'de post bulundu)
		verify(apifyClient, never()).fetchPostsByUrls(anyList(), anyInt());
		// Cache'e yazıldı
		ArgumentCaptor<UserAccountDna> savedCaptor = ArgumentCaptor.forClass(UserAccountDna.class);
		verify(userAccountDnaRepository, times(1)).save(savedCaptor.capture());
		UserAccountDna saved = savedCaptor.getValue();
		assertEquals(userId, saved.getUserId());
		assertEquals(socialAccountId, saved.getSocialAccountId());
		assertEquals("{\"mainProductOrService\":\"kebap\"}", saved.getDnaJson());
		assertEquals(2, saved.getSourcePostCount());

		// Prompt'a caption ve görsel analiz verisi gitti mi (AI çağrı argümanı üzerinden doğrula)
		ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
		verify(aiAnalysisService).generateBrandDna(promptCaptor.capture());
		assertTrue(promptCaptor.getValue().contains("Adana kebap tabağımız"));
		assertTrue(promptCaptor.getValue().contains("[KENDİ]"));
	}

	@Test
	void hesapSeciliVeDnaVarsaCacheKullanilirAnalizVeScrapeCagrilmaz() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID socialAccountId = UUID.randomUUID();

		when(jdbcTemplate.queryForList(contains("user_account_dna"), eq(String.class), any(Object[].class)))
				.thenReturn(List.of("{\"mainProductOrService\":\"kebap\"}"));

		Method resolveAccountDna = ContentPipelineService.class.getDeclaredMethod(
				"resolveAccountDna", UUID.class, UUID.class, String.class);
		resolveAccountDna.setAccessible(true);
		String dna = (String) resolveAccountDna.invoke(service, userId, socialAccountId, null);

		assertEquals("{\"mainProductOrService\":\"kebap\"}", dna);
		verify(aiAnalysisService, never()).generateBrandDna(anyString());
		verify(apifyClient, never()).fetchPostsByUrls(anyList(), anyInt());
		verify(userAccountDnaRepository, never()).save(any());
		// DB'de post sorgusu (report_request join'i) hiç çalışmadı — cache hit'te başka hiçbir şey yapılmaz
		verify(jdbcTemplate, never()).query(contains("report_request"), any(RowMapper.class), any(Object[].class));
	}

	@Test
	void hesapSecilmediyseDnaHicUretilmez() {
		ContentRequest req = sampleRequest();
		req.setSocialAccountId(null);

		when(contentRequestRepository.findById(req.getContentRequestId())).thenReturn(Optional.of(req));

		service.process(req.getContentRequestId());

		verifyNoInteractions(userAccountDnaRepository);
		verifyNoInteractions(apifyClient);
		assertNull(req.getBrandDnaJson());
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
		req.setContentType(ContentType.POST);
		return req;
	}

	private ResultSet mockPostRow(String caption, String analysisJson) throws SQLException {
		ResultSet rs = mock(ResultSet.class);
		when(rs.getString("caption")).thenReturn(caption);
		when(rs.getString("analysis_json")).thenReturn(analysisJson);
		return rs;
	}
}
