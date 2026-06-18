package com.api.messaging;

import java.util.UUID;

/**
 * Kuyruğa basılan mesaj gövdesi (CLAUDE.md Bölüm 9 — yeni: scheduler yok, direkt push).
 * Mesaj = request_id; worker bu id ile report_request'i yükleyip pipeline'ı çalıştırır.
 *
 * @param requestId işlenecek rapor isteğinin PK'si
 */
public record JobMessage(UUID requestId) {
}
