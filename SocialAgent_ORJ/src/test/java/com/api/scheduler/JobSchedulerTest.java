package com.api.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import com.api.messaging.JobQueueProducer;

/**
 * JobScheduler için Spring'siz birim testi (broker/DB gerektirmez).
 * JdbcTemplate ve JobQueueProducer mock'lanır.
 * Doğrulanan davranışlar:
 *  - Aday job claim edilince (UPDATE 1 satır) kuyruğa basılır.
 *  - Claim başarısız olunca (UPDATE 0 satır) kuyruğa basılmaz.
 *  - Publish hata atarsa claim geri alınır (ikinci UPDATE çağrısı).
 */
class JobSchedulerTest {

	// Mock bağımlılıklar
	private JdbcTemplate jdbcTemplate;
	private JobQueueProducer producer;
	private JobScheduler scheduler;

	private final UUID jobId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		producer = org.mockito.Mockito.mock(JobQueueProducer.class);
		scheduler = new JobScheduler(jdbcTemplate, producer);
	}

	@SuppressWarnings("unchecked")
	@Test
	void claimBasariliIseKuyrugaBasilir() {
		// SELECT -> bir aday job döndür
		when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
				.thenReturn(List.of(jobId));
		// Claim UPDATE -> 1 satır (claim başarılı)
		when(jdbcTemplate.update(anyString(), (Object[]) any()))
				.thenReturn(1);

		// Tara
		scheduler.pollAndQueueJobs();

		// Producer tam 1 kez çağrılmalı
		verify(producer, times(1)).publishJob(eq(jobId));
	}

	@SuppressWarnings("unchecked")
	@Test
	void claimBasarisizIseKuyrugaBasilmaz() {
		// SELECT -> bir aday job
		when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
				.thenReturn(List.of(jobId));
		// Claim UPDATE -> 0 satır (başka instance almış)
		when(jdbcTemplate.update(anyString(), (Object[]) any()))
				.thenReturn(0);

		scheduler.pollAndQueueJobs();

		// Hiç kuyruğa basılmamalı
		verify(producer, never()).publishJob(any());
	}

	@SuppressWarnings("unchecked")
	@Test
	void publishHataAtarsaClaimGeriAlinir() {
		// SELECT -> bir aday job
		when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
				.thenReturn(List.of(jobId));
		// Tüm UPDATE'ler 1 satır döner (claim ve release)
		when(jdbcTemplate.update(anyString(), (Object[]) any()))
				.thenReturn(1);
		// Publish patlasın (broker erişilemez senaryosu)
		org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
				.when(producer).publishJob(any());

		scheduler.pollAndQueueJobs();

		// UPDATE en az 2 kez çağrılmalı: 1) claim, 2) release
		verify(jdbcTemplate, times(2)).update(anyString(), (Object[]) any());
	}
}
