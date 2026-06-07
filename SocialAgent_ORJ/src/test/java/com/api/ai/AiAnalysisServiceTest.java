package com.api.ai;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.api.config.AppProperties;
import com.api.entity.SocialPost;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * AiAnalysisService için Spring'siz birim testi (gerçek AI/ağ gerektirmez).
 * Modeller reflection ile mock ChatLanguageModel'lerle değiştirilir.
 *
 * Yeni davranış (WorkerPrompt revizyonu): analyzeFull() her post için
 * hem OpenAI (metrik analizi) hem Gemini Vision (görsel analiz) çalıştırır.
 * Sonuç {"metrics":{...},"visual":{...}} birleşik JSON'udur.
 */
class AiAnalysisServiceTest {

	private ChatLanguageModel openAiModel;
	private ChatLanguageModel geminiModel;
	private AiAnalysisService service;

	@BeforeEach
	void setUp() throws Exception {
		service = new AiAnalysisService(new AppProperties());
		openAiModel = org.mockito.Mockito.mock(ChatLanguageModel.class);
		geminiModel = org.mockito.Mockito.mock(ChatLanguageModel.class);
		setField("openAiModel", openAiModel);
		setField("geminiModel", geminiModel);
	}

	@Test
	void herPostIcinHerIkiModelCalisir() {
		// OpenAI metrik analizi JSON döndürsün
		when(openAiModel.chat(anyString())).thenReturn("{\"engagement\":{\"level\":\"HIGH\"}}");
		// Gemini görsel analizi JSON döndürsün
		AiMessage aiMsg = AiMessage.from("{\"hasHuman\":true}");
		ChatResponse response = ChatResponse.builder().aiMessage(aiMsg).build();
		when(geminiModel.chat(any(UserMessage.class))).thenReturn(response);

		String json = service.analyzeFull(postWithMedia());

		// Her iki model de çağrılmalı
		verify(openAiModel, times(1)).chat(anyString());
		verify(geminiModel, times(1)).chat(any(UserMessage.class));
		// Birleşik JSON döner: metrics + visual anahtarları içermeli
		assertNotNull(json);
		assertTrue(json.contains("metrics"));
		assertTrue(json.contains("visual"));
	}

	@Test
	void openAiYoksaSadaceGeminiSonucuDoner() {
		// OpenAI modeli null (key yok) -> metrik analizi atlanır
		setFieldSilent("openAiModel", null);
		AiMessage aiMsg = AiMessage.from("{\"hasHuman\":false}");
		ChatResponse response = ChatResponse.builder().aiMessage(aiMsg).build();
		when(geminiModel.chat(any(UserMessage.class))).thenReturn(response);

		String json = service.analyzeFull(postWithMedia());

		// Sadece Gemini çalışır; metrics null, visual dolu → birleşik JSON döner
		assertNotNull(json);
		assertTrue(json.contains("\"metrics\":null"));
		assertTrue(json.contains("visual"));
	}

	@Test
	void geminiYoksaSadaceOpenAiSonucuDoner() {
		// Gemini modeli null (key yok) -> görsel analiz atlanır
		setFieldSilent("geminiModel", null);
		when(openAiModel.chat(anyString())).thenReturn("{\"engagement\":{\"level\":\"LOW\"}}");

		String json = service.analyzeFull(postWithMedia());

		// Sadece OpenAI çalışır; metrics dolu, visual null → birleşik JSON döner
		assertNotNull(json);
		assertTrue(json.contains("metrics"));
		assertTrue(json.contains("\"visual\":null"));
	}

	@Test
	void herIkiModelYoksaNullDoner() {
		// Her iki model de null -> hiçbir analiz yapılamaz
		setFieldSilent("openAiModel", null);
		setFieldSilent("geminiModel", null);

		String json = service.analyzeFull(postWithMedia());

		assertNull(json);
	}

	// ---- yardımcılar ----

	private SocialPost postWithMedia() {
		SocialPost sp = new SocialPost();
		sp.setSocialPostId(UUID.randomUUID());
		sp.setPlatform("INSTAGRAM");
		sp.setMediaType("IMAGE");
		sp.setMediaUrl("https://cdn.example.com/img.jpg");
		sp.setCaption("Test gönderisi");
		sp.setResultJson("{\"id\":\"abc\",\"likesCount\":120,\"commentsCount\":8}");
		sp.setLikesCount(120L);
		sp.setCommentsCount(8L);
		return sp;
	}

	private void setField(String name, Object value) throws Exception {
		Field f = AiAnalysisService.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(service, value);
	}

	private void setFieldSilent(String name, Object value) {
		try {
			setField(name, value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
