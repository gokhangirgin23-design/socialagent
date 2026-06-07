package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.entity.UserJob;

/**
 * JobCompletionService için Spring'siz birim testi (DB gerektirmez).
 * JdbcTemplate mock'lanır; UPDATE parametreleri ArgumentCaptor ile doğrulanır.
 * UPDATE parametre sırası: [current_count, completed, active, next_run_date, updated_date, user_job_id]
 *
 * Doğrulanan davranışlar (FAZ 7 + active revizyonu):
 *  - ON_DEMAND -> completed=1, active=0, next_run_date=NULL.
 *  - WEEKLY & repeat hedefine ulaşınca -> completed=1, active=0, next_run_date=NULL.
 *  - WEEKLY & devam ederken -> completed=0, active=1, next_run_date dolu (yeniden zamanlama).
 *  - Job yoksa UPDATE yapılmaz.
 */
class JobCompletionServiceTest {

	private JdbcTemplate jdbcTemplate;
	private JobCompletionService service;

	private final UUID jobId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		service = new JobCompletionService(jdbcTemplate);
	}

	@SuppressWarnings("unchecked")
	@Test
	void onDemandTamamlanir() {
		// ON_DEMAND, repeat yok, current=0
		mockJob("ON_DEMAND", null, 0);

		service.finalizeJob(jobId);

		Object[] args = captureUpdateArgs();
		// current_count 0 -> 1
		assertEquals(1, args[0]);
		// completed = 1 (tek seferlik)
		assertEquals(1, args[1]);
		// active = 0 (tamamlandı -> pasifleşir)
		assertEquals(0, args[2]);
		// next_run_date NULL (tamamlandı)
		assertNull(args[3]);
	}

	@SuppressWarnings("unchecked")
	@Test
	void recurringHedefeUlasincaTamamlanir() {
		// WEEKLY, repeat=2, current=1 -> yeni count 2 >= 2 -> tamamlanır
		mockJob("WEEKLY", 2, 1);

		service.finalizeJob(jobId);

		Object[] args = captureUpdateArgs();
		assertEquals(2, args[0]);     // current_count 1 -> 2
		assertEquals(1, args[1]);     // completed = 1
		assertEquals(0, args[2]);     // active = 0 (tamamlandı)
		assertNull(args[3]);          // next_run_date NULL
	}

	@SuppressWarnings("unchecked")
	@Test
	void recurringDevamEder() {
		// WEEKLY, repeat=4, current=1 -> yeni count 2 < 4 -> devam, yeniden zamanla
		mockJob("WEEKLY", 4, 1);

		service.finalizeJob(jobId);

		Object[] args = captureUpdateArgs();
		assertEquals(2, args[0]);     // current_count 1 -> 2
		assertEquals(0, args[1]);     // completed = 0 (devam ediyor)
		assertEquals(1, args[2]);     // active = 1 (devam ediyor)
		assertNotNull(args[3]);       // next_run_date dolu (now + 1 hafta)
	}

	@SuppressWarnings("unchecked")
	@Test
	void jobYoksaUpdateYapilmaz() {
		// Lookup boş -> job yok
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of());

		service.finalizeJob(jobId);

		// Hiç UPDATE yapılmamalı
		verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
	}

	// ---- yardımcılar ----

	// loadJobForFinalize sorgusunu verilen kolonlarla mock'lar
	@SuppressWarnings("unchecked")
	private void mockJob(String period, Integer repeat, Integer current) {
		UserJob j = new UserJob();
		j.setUserJobId(jobId);
		j.setJobPeriod(period);
		j.setRepeatCount(repeat);
		j.setCurrentCount(current);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
				.thenReturn(List.of(j));
	}

	// finalizeJob içindeki tek UPDATE çağrısının vararg parametrelerini yakalar
	private Object[] captureUpdateArgs() {
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(jdbcTemplate, times(1)).update(anyString(), captor.capture());
		return captor.getAllValues().toArray();
	}
}
