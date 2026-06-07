package com.api.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.common.ApiException;
import com.api.common.ResponseCode;
import com.api.dto.CreateJobRequest;
import com.api.dto.UserJobDto;
import com.api.dto.repository.UserJobRepository;
import com.api.entity.AnalysisMode;
import com.api.entity.JobPeriod;
import com.api.entity.UserJob;
import com.api.mapper.UserJobMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Job oluşturma ve listeleme iş mantığı (concrete; interface yok — CLAUDE.md Madde 1).
 * analysisMode kullanıcının hesap durumuna göre otomatik belirlenir (CLAUDE.md Bölüm 8).
 * Lookup'lar JdbcTemplate native; insert JPA save.
 * Türkçe yorum çoğu satırda (CLAUDE.md Madde 7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

	// Native sorgular için JdbcTemplate
	private final JdbcTemplate jdbcTemplate;

	// Job insert için JPA repository
	private final UserJobRepository userJobRepository;

	// UserJob entity -> DTO dönüştürücü
	private final UserJobMapper userJobMapper;

	// analysisPeriodDays istekten alınmaz; tüm job'larda uygulanan varsayılan tekrar-analiz penceresi (gün)
	private static final int DEFAULT_ANALYSIS_PERIOD_DAYS = 3;

	// UserJob satırlarını entity'ye çeviren RowMapper (liste sorguları için)
	private static final RowMapper<UserJob> JOB_ROW_MAPPER = (rs, rowNum) -> {
		UserJob j = new UserJob();
		j.setUserJobId(rs.getObject("user_job_id", UUID.class));
		j.setUserId(rs.getObject("user_id", UUID.class));
		// selectedUserSocialAccountId nullable
		j.setSelectedUserSocialAccountId(rs.getObject("selected_user_social_account_id", UUID.class));
		j.setAnalysisMode(rs.getString("analysis_mode"));
		j.setJobPeriod(rs.getString("job_period"));
		j.setAnalysisPeriodDays(rs.getObject("analysis_period_days", Integer.class));
		j.setRepeatCount(rs.getObject("repeat_count", Integer.class));
		j.setCurrentCount(rs.getObject("current_count", Integer.class));
		j.setCompleted(rs.getObject("completed", Integer.class));
		j.setActive(rs.getObject("active", Integer.class));
		if (rs.getTimestamp("created_date") != null) {
			j.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
		}
		if (rs.getTimestamp("updated_date") != null) {
			j.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
		}
		return j;
	};

	/**
	 * Yeni analiz job'ı oluşturur.
	 * analysisMode kullanıcının mevcut hesaplarına göre otomatik seçilir.
	 * ON_DEMAND dışında repeatCount zorunludur.
	 * Endpoint: POST /job/create
	 */
	@Transactional
	public UserJobDto createJob(UUID userId, CreateJobRequest req) {
		LocalDateTime now = LocalDateTime.now();

		// 1) jobPeriod geçerli bir enum değeri mi?
		JobPeriod period = parseJobPeriod(req.getJobPeriod());

		// 2) Sektör zorunlu: kullanıcı sektör seçmemişse job oluşturulamaz (subsector opsiyonel).
		//    Sektör, NONE/OWN_ONLY modlarında hashtag araştırmasının girdisidir.
		if (!hasSectorSelected(userId)) {
			throw new ApiException(ResponseCode.VALIDATION_ERROR,
					"Job oluşturmadan önce sektör seçilmelidir");
		}

		// 3) ON_DEMAND ("şu an") tek seferliktir; repeatCount gönderilmemelidir
		if (period == JobPeriod.ON_DEMAND && req.getRepeatCount() != null) {
			throw new ApiException(ResponseCode.VALIDATION_ERROR,
					"ON_DEMAND (şu an) seçildiğinde repeatCount gönderilemez");
		}

		// 4) ON_DEMAND değilse repeatCount zorunlu
		if (period != JobPeriod.ON_DEMAND && req.getRepeatCount() == null) {
			throw new ApiException(ResponseCode.VALIDATION_ERROR,
					"ON_DEMAND dışı periyotlarda repeatCount zorunludur");
		}

		// 5) analysisPeriodDays istekten alınmaz; backend default uygular (tekrar-analiz penceresi)
		int analysisPeriodDays = DEFAULT_ANALYSIS_PERIOD_DAYS;

		// 4) Kullanıcının kendi hesabı var mı? (active=1)
		String ownAccountSql = """
				SELECT user_social_account_id
				FROM user_social_account
				WHERE user_id = ? AND active = 1
				LIMIT 1
				""";
		List<UUID> ownAccounts = jdbcTemplate.query(ownAccountSql,
				(rs, rowNum) -> rs.getObject("user_social_account_id", UUID.class),
				userId);
		boolean hasOwnAccount = !ownAccounts.isEmpty();
		// Kendi hesabının id'si (OWN_ONLY / BOTH durumunda kullanılacak)
		UUID ownAccountId = hasOwnAccount ? ownAccounts.get(0) : null;

		// 5) Kullanıcının izlediği rakip hesap var mı? (active=1)
		String monitoredSql = """
				SELECT user_monitored_account_id
				FROM user_monitored_account
				WHERE user_id = ? AND active = 1
				LIMIT 1
				""";
		List<UUID> monitoredAccounts = jdbcTemplate.query(monitoredSql,
				(rs, rowNum) -> rs.getObject("user_monitored_account_id", UUID.class),
				userId);
		boolean hasMonitoredAccount = !monitoredAccounts.isEmpty();

		// 6) analysisMode otomatik belirleme (CLAUDE.md Bölüm 8)
		AnalysisMode analysisMode;
		UUID selectedUserSocialAccountId;
		if (hasOwnAccount && hasMonitoredAccount) {
			// Her ikisi de var: BOTH modu
			analysisMode = AnalysisMode.BOTH;
			selectedUserSocialAccountId = ownAccountId;
		} else if (hasOwnAccount) {
			// Sadece kendi hesabı var
			analysisMode = AnalysisMode.OWN_ONLY;
			selectedUserSocialAccountId = ownAccountId;
		} else if (hasMonitoredAccount) {
			// Sadece rakip hesap var
			analysisMode = AnalysisMode.COMPETITOR_ONLY;
			selectedUserSocialAccountId = null; // kendi hesabı yok
		} else {
			// Ne kendi ne rakip; sektör top 5 üzerinden analiz
			analysisMode = AnalysisMode.NONE;
			selectedUserSocialAccountId = null;
		}

		// 7) UserJob entity'si oluştur
		UserJob job = new UserJob();
		job.setUserJobId(UUID.randomUUID());
		job.setUserId(userId);
		job.setSelectedUserSocialAccountId(selectedUserSocialAccountId);
		// Enum değerini string olarak sakla (DB'de VARCHAR)
		job.setAnalysisMode(analysisMode.name());
		job.setJobPeriod(period.name());
		job.setAnalysisPeriodDays(analysisPeriodDays);
		// ON_DEMAND ise repeatCount null; diğerlerinde istekten gelen değer
		job.setRepeatCount(period == JobPeriod.ON_DEMAND ? null : req.getRepeatCount());
		// Başlangıç sayacı sıfır
		job.setCurrentCount(0);
		// Henüz tamamlanmadı
		job.setCompleted(0);
		// Scheduler henüz kuyruğa basmadı (V3 kolonu, NOT NULL)
		job.setQueued(0);
		// Aktif olarak başlar
		job.setActive(1);
		job.setCreatedDate(now);
		job.setUpdatedDate(now);

		// 8) JPA save ile insert
		UserJob saved = userJobRepository.save(job);

		// 9) Oluşturulan job'ı DTO olarak döndür
		return userJobMapper.toDto(saved);
	}

	/**
	 * Kullanıcının job'larını sayfalı listeler.
	 * En yeni job en başta gelir (created_date DESC).
	 * Endpoint: POST /job/list
	 */
	@Transactional(readOnly = true)
	public List<UserJobDto> listJobs(UUID userId, int page, int size) {
		// Negatif sayfa/boyut koruması
		int safePage = Math.max(page, 0);
		int safeSize = (size > 0) ? size : 10;
		// OFFSET hesapla
		int offset = safePage * safeSize;

		// Kullanıcının job'larını sayfalı çek.
		// Tamamlanınca active=0 yapıldığından (iş sonu muhasebesi), tamamlanan job'lar da
		// listelenebilsin diye filtre: active=1 (devam eden) VEYA completed=1 (tamamlanmış).
		String sql = """
				SELECT user_job_id, user_id, selected_user_social_account_id,
				       analysis_mode, job_period, analysis_period_days,
				       repeat_count, current_count, completed, active,
				       created_date, updated_date
				FROM user_job
				WHERE user_id = ? AND (active = 1 OR completed = 1)
				ORDER BY created_date DESC
				LIMIT ? OFFSET ?
				""";
		// JdbcTemplate ile liste çek (? sırasıyla: userId, size, offset)
		List<UserJob> jobs = jdbcTemplate.query(sql, JOB_ROW_MAPPER, userId, safeSize, offset);

		// MapStruct ile DTO listesine dönüştür
		return userJobMapper.toDtoList(jobs);
	}

	// ============================================================
	// Yardımcı metodlar
	// ============================================================

	/**
	 * Kullanıcı sektör seçmiş mi? (job oluşturma ön koşulu — subsector opsiyonel).
	 * user_info.sector_id NULL değilse true.
	 */
	private boolean hasSectorSelected(UUID userId) {
		String sql = """
				SELECT sector_id
				FROM user_info
				WHERE user_id = ? AND active = 1
				""";
		List<UUID> rows = jdbcTemplate.query(sql,
				(rs, rowNum) -> rs.getObject("sector_id", UUID.class),
				userId);
		// Kayıt yoksa ya da sector_id NULL ise sektör seçilmemiştir
		return !rows.isEmpty() && rows.get(0) != null;
	}

	/**
	 * String değeri JobPeriod enum'una çevirir; geçersiz değerde VALIDATION_ERROR fırlatır.
	 */
	private JobPeriod parseJobPeriod(String value) {
		try {
			// Büyük/küçük harf fark etmemesi için uppercase normalize et
			return JobPeriod.valueOf(value.toUpperCase());
		} catch (IllegalArgumentException e) {
			// Geçersiz enum değeri
			throw new ApiException(ResponseCode.VALIDATION_ERROR,
					"Geçersiz jobPeriod değeri: " + value + ". Geçerli değerler: DAILY, WEEKLY, MONTHLY, ON_DEMAND");
		}
	}
}
