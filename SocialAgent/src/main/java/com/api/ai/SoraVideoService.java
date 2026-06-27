package com.api.ai;

import java.io.InputStream;
import java.net.URI;
import java.util.Base64;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.api.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI Sora video üretim servisi.
 * REEL içerik tipinde statik görsel yerine 9:16 dikey video (max 30 sn) üretir.
 *
 * Akış: POST /v1/video/generations → async → polling → video byte indir
 * API key tanımlı değilse tüm çağrılar null döner; pipeline çökmez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoraVideoService {

    private static final String BASE_URL = "https://api.openai.com/v1";
    private static final int MAX_POLL_ATTEMPTS = 72;   // 6 dakika (5s * 72)
    private static final int POLL_INTERVAL_MS  = 5_000;

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestClient restClient;
    private String apiKey;
    private String videoModel;

    @PostConstruct
    void init() {
        this.apiKey    = appProperties.getAi().getOpenai().getApiKey();
        this.videoModel = appProperties.getContent().getVideoModel();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI API key tanımsız; Sora video üretimi devre dışı.");
            return;
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(30_000);

        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();

        log.info("Sora video service hazır: model={}", videoModel);
    }

    public boolean isActive() {
        return restClient != null;
    }

    /**
     * Prompt'a göre 9:16 dikey, max 30 saniyelik Reel videosu üretir.
     *
     * @param prompt video içerik açıklaması
     * @return MP4 byte dizisi; hata veya servis pasif ise null
     */
    public byte[] generateVideo(String prompt) {
        if (restClient == null) {
            log.info("Sora service aktif değil; video üretimi atlandı.");
            return null;
        }
        try {
            Map<String, Object> body = Map.of(
                    "model",      videoModel,
                    "prompt",     prompt,
                    "resolution", "1080x1920",
                    "duration",   30,
                    "n",          1
            );

            String createJson = restClient.post()
                    .uri("/video/generations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(createJson);

            if (!root.path("error").isMissingNode()) {
                log.error("Sora create hatası: {}", root.path("error"));
                return null;
            }

            // Senkron yanıt — generations hemen geldiyse indir
            if (!root.path("generations").isMissingNode()) {
                return downloadFromGenerations(root);
            }

            // Asenkron — job id ile polling
            String jobId = root.path("id").asText(null);
            if (jobId == null || jobId.isBlank()) {
                log.warn("Sora: job id bulunamadı. Yanıt: {}",
                        createJson.substring(0, Math.min(400, createJson.length())));
                return null;
            }
            log.info("Sora video kuyruğa alındı: jobId={}", jobId);
            return pollAndDownload(jobId);

        } catch (RestClientResponseException ex) {
            log.error("Sora API HTTP hatası: status={}, body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            return null;
        } catch (Exception ex) {
            log.error("Sora video üretimi başarısız: {}", ex.getMessage(), ex);
            return null;
        }
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    private byte[] pollAndDownload(String jobId) throws Exception {
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            Thread.sleep(POLL_INTERVAL_MS);

            String statusJson = restClient.get()
                    .uri("/video/generations/" + jobId)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(statusJson);
            String status = root.path("status").asText("");

            log.debug("Sora polling: jobId={}, status={}, attempt={}/{}", jobId, status, i + 1, MAX_POLL_ATTEMPTS);

            switch (status.toLowerCase()) {
                case "completed" -> { return downloadFromGenerations(root); }
                case "failed", "cancelled" -> {
                    log.error("Sora video başarısız: jobId={}, status={}", jobId, status);
                    return null;
                }
            }
        }
        log.error("Sora polling zaman aşımı: jobId={}", jobId);
        return null;
    }

    private byte[] downloadFromGenerations(JsonNode root) throws Exception {
        JsonNode generations = root.path("generations");
        if (generations.isEmpty()) {
            log.warn("Sora: generations dizisi boş");
            return null;
        }
        JsonNode first = generations.get(0);

        // URL ile indir
        String videoUrl = first.path("url").asText(null);
        if (videoUrl != null && !videoUrl.isBlank()) {
            log.info("Sora video indiriliyor: url uzunluğu={}", videoUrl.length());
            try (InputStream is = URI.create(videoUrl).toURL().openStream()) {
                byte[] bytes = is.readAllBytes();
                log.info("Sora video indirildi: {} bytes", bytes.length);
                return bytes;
            }
        }

        // b64_json ile decode et (fallback)
        String b64 = first.path("b64_json").asText(null);
        if (b64 != null && !b64.isBlank()) {
            return Base64.getDecoder().decode(b64);
        }

        log.warn("Sora: video URL veya b64_json bulunamadı. Yanıt: {}", first);
        return null;
    }
}
