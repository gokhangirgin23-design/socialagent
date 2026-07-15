package com.api.ai;

import java.util.Locale;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
// SoraVideoService devre dışı — Veo ile değiştirildi (VeoVideoService.java)
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
 * REEL içerik tipinde 9:16 dikey video üretir.
 *
 * Sora API kısıtları (sora-2): seconds = 4 | 8 | 12, size = 720x1280 (portrait 9:16)
 * Akış: POST /v1/videos → polling GET /v1/videos/{id} → download /v1/videos/{id}/content
 */
@Slf4j
@RequiredArgsConstructor
public class SoraVideoService {

    private static final String BASE_URL    = "https://api.openai.com/v1";
    private static final int MAX_POLL       = 120;        // 10 dakika (5s * 120)
    private static final int POLL_DELAY_MS  = 5_000;
    private static final String REEL_SIZE   = "720x1280"; // 9:16 portrait
    private static final int REEL_SECONDS   = 12;         // Sora max: 4 | 8 | 12

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestClient restClient;
    private String videoModel;

    @PostConstruct
    void init() {
        String apiKey = appProperties.getAi().getOpenai().getApiKey();
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

        log.info("Sora video service hazır: model={}, size={}, seconds={}",
                videoModel, REEL_SIZE, REEL_SECONDS);
    }

    public boolean isActive() {
        return restClient != null;
    }

    /**
     * Prompt'a göre 9:16 dikey Reel videosu üretir (12 sn, 720×1280).
     *
     * @return MP4 byte dizisi; hata veya servis pasif ise null
     */
    public byte[] generateVideo(String prompt) {
        if (restClient == null) {
            log.info("Sora service aktif değil; video üretimi atlandı.");
            return null;
        }
        try {
            Map<String, Object> body = Map.of(
                    "model",   videoModel,
                    "prompt",  prompt,
                    "size",    REEL_SIZE,
                    "seconds", REEL_SECONDS
            );

            String createJson = restClient.post()
                    .uri("/videos")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(createJson);

            if (!root.path("error").isMissingNode()) {
                log.error("Sora create hatası: {}", root.path("error"));
                return null;
            }

            String videoId = root.path("id").asText(null);
            if (videoId == null || videoId.isBlank()) {
                log.warn("Sora: video id bulunamadı. Yanıt: {}",
                        createJson.substring(0, Math.min(400, createJson.length())));
                return null;
            }

            log.info("Sora video oluşturuldu: id={}, durum={}", videoId, root.path("status").asText());
            return pollAndDownload(videoId);

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

    private byte[] pollAndDownload(String videoId) throws Exception {
        for (int i = 0; i < MAX_POLL; i++) {
            Thread.sleep(POLL_DELAY_MS);

            String statusJson = restClient.get()
                    .uri("/videos/" + videoId)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(statusJson);
            String status = root.path("status").asText("");

            log.debug("Sora polling: id={}, status={}, attempt={}/{}", videoId, status, i + 1, MAX_POLL);

            switch (status.toLowerCase(Locale.ROOT)) {
                case "completed" -> { return downloadContent(videoId); }
                case "failed"    -> {
                    log.error("Sora video başarısız: id={}", videoId);
                    return null;
                }
            }
        }
        log.error("Sora polling zaman aşımı: id={}", videoId);
        return null;
    }

    private byte[] downloadContent(String videoId) {
        try {
            byte[] bytes = restClient.get()
                    .uri("/videos/" + videoId + "/content")
                    .retrieve()
                    .body(byte[].class);
            log.info("Sora video indirildi: id={}, {} bytes", videoId, bytes == null ? 0 : bytes.length);
            return bytes;
        } catch (Exception ex) {
            log.error("Sora video indirme başarısız: id={}, hata={}", videoId, ex.getMessage());
            return null;
        }
    }
}
