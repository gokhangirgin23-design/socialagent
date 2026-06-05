package com.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Rapor detayını (Markdown içerik dahil) getirme isteği (POST /report/detail body) — FAZ 8.
 * userId JWT'den alınır; rapor yalnızca sahibi olan kullanıcıya döner (ownership join — CLAUDE.md Madde 6).
 */
@Getter
@Setter
public class ReportDetailRequest {

	// Detayı istenen raporun id'si (zorunlu)
	@NotNull(message = "reportId zorunludur")
	private UUID reportId;
}
