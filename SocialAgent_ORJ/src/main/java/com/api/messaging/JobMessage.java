package com.api.messaging;

import java.util.UUID;

/**
 * Job kuyruğuna basılan mesaj gövdesi (FAZ 4 — CLAUDE.md Bölüm 9).
 * Spec'e göre mesaj = user_job_id; tip güvenliği için record içinde taşınır.
 * FAZ 5 worker bu id ile job'ı yükleyip Apify + AI pipeline'ını çalıştırır.
 *
 * @param userJobId işlenecek job'ın PK'si
 */
public record JobMessage(UUID userJobId) {
}
