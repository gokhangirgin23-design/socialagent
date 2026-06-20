package com.api.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.dto.repository.PostAnalysisRepository;
import com.api.entity.PostAnalysis;

/**
 * PostAnalysisService için Spring'siz birim testi (DB gerektirmez).
 * JdbcTemplate ve PostAnalysisRepository mock'lanır.
 * Doğrulanan davranışlar (FAZ 6):
 *  - saveAnalysis: yeni JSON + kayıt yoksa save çağrılır (true).
 *  - saveAnalysis: zaten analiz varsa save çağrılmaz (false).
 *  - saveAnalysis: JSON boş/null ise save çağrılmaz (false).
 */
class PostAnalysisServiceTest {

	private JdbcTemplate jdbcTemplate;
	private PostAnalysisRepository postAnalysisRepository;
	private PostAnalysisService service;

	private final UUID postId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		postAnalysisRepository = org.mockito.Mockito.mock(PostAnalysisRepository.class);
		// @RequiredArgsConstructor sırası: jdbcTemplate, postAnalysisRepository
		service = new PostAnalysisService(jdbcTemplate, postAnalysisRepository);
	}

	@SuppressWarnings("unchecked")
	@Test
	void yeniAnalizKaydedilir() {
		// Dedup sorgusu boş -> kayıt yok, yazılmalı
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		boolean saved = service.saveAnalysis(postId, "{\"tone\":\"samimi\"}");

		// Yeni kayıt -> saveAndFlush çağrılır (immediate flush), sonuç true
		verify(postAnalysisRepository, times(1)).saveAndFlush(any(PostAnalysis.class));
		assertTrue(saved);
	}

	@SuppressWarnings("unchecked")
	@Test
	void zatenAnalizVarsaYazilmaz() {
		// Dedup sorgusu dolu -> zaten analiz var
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(UUID.randomUUID()));

		boolean saved = service.saveAnalysis(postId, "{\"tone\":\"samimi\"}");

		// Zaten var -> saveAndFlush çağrılmaz, sonuç false
		verify(postAnalysisRepository, never()).saveAndFlush(any(PostAnalysis.class));
		assertFalse(saved);
	}

	@Test
	void bosJsonYazilmaz() {
		// AI sonuç üretmediyse (null/blank) hiç sorgu/insert yapılmamalı
		boolean savedNull = service.saveAnalysis(postId, null);
		boolean savedBlank = service.saveAnalysis(postId, "   ");

		verify(postAnalysisRepository, never()).saveAndFlush(any(PostAnalysis.class));
		assertFalse(savedNull);
		assertFalse(savedBlank);
	}
}
