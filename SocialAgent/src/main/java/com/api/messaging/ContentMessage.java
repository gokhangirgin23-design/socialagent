package com.api.messaging;

import java.util.UUID;

/**
 * İçerik üretim kuyruğuna basılan mesaj gövdesi.
 * Worker bu id ile content_request'i yükleyip pipeline'ı çalıştırır.
 *
 * @param contentRequestId işlenecek içerik isteğinin PK'si
 */
public record ContentMessage(UUID contentRequestId) {
}
