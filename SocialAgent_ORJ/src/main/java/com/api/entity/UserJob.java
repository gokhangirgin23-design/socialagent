package com.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcı analiz işi (user_job tablosu).
 * Her job; scraping + AI analiz + rapor üretim zincirini temsil eder.
 * Scheduler bu tabloyu okuyarak kuyruğa gönderir (CLAUDE.md Bölüm 9).
 * Insert sonrası active=1, completed=0 olarak başlar.
 */
@Entity
@Table(name = "user_job")
@Getter
@Setter
public class UserJob {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "user_job_id")
	private UUID userJobId;

	// Job'ı oluşturan kullanıcı (nesne referansı yok - CLAUDE.md Madde 6)
	@Column(name = "user_id")
	private UUID userId;

	// Analiz edilecek kendi hesabının id'si (OWN_ONLY/BOTH durumunda dolu, diğerinde null)
	@Column(name = "selected_user_social_account_id")
	private UUID selectedUserSocialAccountId;

	// Analiz modu: OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE (AnalysisMode enum)
	@Column(name = "analysis_mode")
	private String analysisMode;

	// Job periyodu: DAILY | WEEKLY | MONTHLY | ON_DEMAND (JobPeriod enum)
	@Column(name = "job_period")
	private String jobPeriod;

	// Tekrar analizi önleme penceresi (gün); varsayılan 7
	@Column(name = "analysis_period_days")
	private Integer analysisPeriodDays;

	// Toplam çalışma sayısı (ON_DEMAND'da null)
	@Column(name = "repeat_count")
	private Integer repeatCount;

	// Şu ana kadar çalışma sayısı (başlangıç 0)
	@Column(name = "current_count")
	private Integer currentCount;

	// İş tamamlandı mı? (0/1); current_count >= repeat_count olunca 1
	@Column(name = "completed")
	private Integer completed;

	// Aktiflik bayrağı (0/1); soft delete için
	@Column(name = "active")
	private Integer active;

	// Kuyruğa basıldı mı? (0/1) — Scheduler idempotent claim için (FAZ 4)
	@Column(name = "queued")
	private Integer queued;

	// Kuyruğa basılma zamanı (claim anı); nullable (FAZ 4)
	@Column(name = "queued_date")
	private LocalDateTime queuedDate;

	// Kaydın oluşturulma tarihi
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
