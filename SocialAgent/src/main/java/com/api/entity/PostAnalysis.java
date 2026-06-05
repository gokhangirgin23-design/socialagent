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
 * Bir social_post için AI analiz sonucu (post_analysis tablosu — CLAUDE.md Bölüm 5, 11).
 * FAZ 6'da LangChain4j ile üretilir: caption/TEXT -> OpenAI, IMAGE/VIDEO/CAROUSEL -> Gemini Vision.
 * Sonuç JSON metni analysisJson kolonuna yazılır (FAZ 7 raporu bunları toplar).
 *
 * İlişkiler nesne referansı ile değil, yalnızca ID kolonu ile tutulur (CLAUDE.md Madde 6).
 * Her social_post için en fazla bir analiz kaydı tutulur (servis katmanında elle kontrol).
 */
@Entity
@Table(name = "post_analysis")
@Getter
@Setter
public class PostAnalysis {

	// Birincil anahtar (UUID, kod tarafında üretilir)
	@Id
	@Column(name = "post_analysis_id")
	private UUID postAnalysisId;

	// Analiz edilen gönderinin id'si (social_post.social_post_id)
	@Column(name = "social_post_id")
	private UUID socialPostId;

	// AI'dan dönen analiz sonucu (JSON metni; prod'da JSONB'ye taşınabilir)
	@Column(name = "analysis_json")
	private String analysisJson;

	// Kaydın oluşturulma tarihi (tekrar-analiz penceresi bu tarihe göre hesaplanır)
	@Column(name = "created_date")
	private LocalDateTime createdDate;

	// Kaydın son güncellenme tarihi
	@Column(name = "updated_date")
	private LocalDateTime updatedDate;
}
