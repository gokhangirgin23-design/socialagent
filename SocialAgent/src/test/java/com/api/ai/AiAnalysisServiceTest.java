package com.api.ai;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Modeller (openAiModel/geminiModel) reflection ile mock ChatLanguageModel'lerle değiştirilir;
 * @PostConstruct init() çağrılmaz (key olmadan model kurulmaz zaten).
 *
 * Doğrulanan davranış (FAZ 6 — D3, yönlendirme kuralı):
 *  - media_type=TEXT  -> OpenAI modeli çağrılır, Gemini ÇAĞRILMAZ.
 *  - media_type=IMAGE -> Gemini modeli çağrılır, OpenAI ÇAĞRILMAZ.
 *  - Model çıktısındaki ``` ve fazla metin temizlenip JSON döndürülür.
 */
class AiAnalysisServiceTest {

	private ChatLanguageModel openAiModel;
	private ChatLanguageModel geminiModel;
	private AiAnalysisService service;

	@BeforeEach
	void setUp() throws Exception {
		// AppProperties yalnızca alan erişimi için; init() çağrılmadığından içeriği önemsiz
		service = new AiAnalysisService(new AppProperties());
		openAiModel = org.mockito.Mockito.mock(ChatLanguageModel.class);
		geminiModel = org.mockito.Mockito.mock(ChatLanguageModel.class);
		// @PostConstruct yerine modelleri reflection ile enjekte et
		setField("openAiModel", openAiModel);
		setField("geminiModel", geminiModel);
	}

	@Test
	void textGonderiOpenAiyeGider() {
		// OpenAI metin çağrısı JSON döndürsün (``` ile sarılı -> temizlenmeli)
		when(openAiModel.chat(anyString())).thenReturn("```json\n{\"tone\":\"samimi\"}\n```");

		String json = service.analyze(textPost());

		// TEXT -> OpenAI çağrılmalı, Gemini hiç çağrılmamalı
		verify(openAiModel, times(1)).chat(anyString());
		verify(geminiModel, never()).chat(any(UserMessage.class));
		// ``` temizlenmiş, geçerli JSON gövdesi dönmeli
		assertNotNull(json);
		org.junit.jupiter.api.Assertions.assertTrue(json.startsWith("{") && json.endsWith("}"));
	}

	@Test
	void gorselGonderiGeminiyeGider() {
		// Gemini multimodal yanıtı (ChatResponse -> AiMessage -> text)
		AiMessage aiMessage = AiMessage.from("{\"tone\":\"kurumsal\"}");
		ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();
		when(geminiModel.chat(any(UserMessage.class))).thenReturn(response);

		String json = service.analyze(imagePost());

		// IMAGE -> Gemini çağrılmalı, OpenAI hiç çağrılmamalı
		verify(geminiModel, times(1)).chat(any(UserMessage.class));
		verify(openAiModel, never()).chat(anyString());
		assertNotNull(json);
		org.junit.jupiter.api.Assertions.assertTrue(json.contains("kurumsal"));
	}

	// ---- yardımcılar ----

	// TEXT türünde örnek gönderi
	private SocialPost textPost() {
		SocialPost sp = basePost();
		sp.setMediaType("TEXT");
		sp.setCaption("Bugün harika bir gün!");
		return sp;
	}

	// IMAGE türünde (görsel URL'li) örnek gönderi
	private SocialPost imagePost() {
		SocialPost sp = basePost();
		sp.setMediaType("IMAGE");
		sp.setMediaUrl("https://cdn.example.com/img.jpg");
		return sp;
	}

	// Ortak alanlar
	private SocialPost basePost() {
		SocialPost sp = new SocialPost();
		sp.setSocialPostId(UUID.randomUUID());
		sp.setPlatform("INSTAGRAM");
		sp.setLikesCount(120L);
		sp.setCommentsCount(8L);
		sp.setViewsCount(0L);
		return sp;
	}

	// Private alanı reflection ile set eder (init() yerine model enjeksiyonu)
	private void setField(String name, Object value) throws Exception {
		Field f = AiAnalysisService.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(service, value);
	}
}
