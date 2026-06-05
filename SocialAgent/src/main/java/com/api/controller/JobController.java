package com.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.dto.CreateJobRequest;
import com.api.dto.JobListRequest;
import com.api.dto.UserJobDto;
import com.api.security.SecurityUtil;
import com.api.service.JobService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Job yönetimi uçları (hepsi POST — CLAUDE.md Madde 2).
 * Tüm uçlar JWT gerektiren güvenli uçlardır.
 * userId daima JWT'den SecurityUtil ile alınır; istekten okunmaz (CLAUDE.md Madde 4).
 */
@RestController
@RequestMapping("/job")
@RequiredArgsConstructor
public class JobController {

	// Job iş mantığı
	private final JobService jobService;

	/**
	 * Yeni analiz job'ı oluşturur.
	 * Onboarding adım 7 — kullanıcının hesap durumuna göre analysisMode otomatik belirlenir.
	 */
	@PostMapping("/create")
	public DataResponse<UserJobDto> createJob(@Valid @RequestBody CreateJobRequest request) {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// İş katmanına devret; oluşturulan job'ı DTO olarak al
		UserJobDto result = jobService.createJob(userId, request);
		return DataResponse.success(result);
	}

	/**
	 * Kullanıcının job'larını sayfalı listeler (en yeni önce).
	 * Dashboard'da kullanılır (POST + body — CLAUDE.md Madde 2).
	 */
	@PostMapping("/list")
	public DataResponse<List<UserJobDto>> listJobs(@RequestBody(required = false) JobListRequest request) {
		// userId JWT'den al
		UUID userId = SecurityUtil.getCurrentUserId();
		// Request null ise varsayılan sayfalama (page=0, size=10) kullan
		int page = (request != null) ? request.getPage() : 0;
		int size = (request != null) ? request.getSize() : 10;
		// İş katmanına devret
		List<UserJobDto> result = jobService.listJobs(userId, page, size);
		return DataResponse.success(result);
	}
}
