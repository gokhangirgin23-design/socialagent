package com.api.ai;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * Google Veo video üretim servisi.
 * REEL içerik tipinde 9:16 dikey kısa video üretir.
 *
 * Veo 3.1 API kısıtları: durationSeconds = 4 | 6 | 8, aspectRatio = "9:16" | "16:9"
 * Akış: POST :predictLongRunning → polling done=true → video URI indir
 * GEMINI_API_KEY env değişkeni kullanılır (Sora'dan farklı key gerekmez).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VeoVideoService {

    private static final String BASE_URL      = "https://generativelanguage.googleapis.com/v1beta";
    private static final int    MAX_POLL      = 120;       // 10 dakika (5s × 120)
    private static final int    POLL_DELAY_MS = 5_000;
    private static final String ASPECT_RATIO  = "9:16";
    private static final String RESOLUTION    = "720p";
    private static final int    DURATION_SECS = 8;         // Veo 3.1 max: 4 | 6 | 8

    private final AppProperties appProperties;
    private final ObjectMapper  objectMapper = new ObjectMapper();

    private RestClient restClient;
    private String     apiKey;
    private String     videoModel;

    @PostConstruct
    void init() {
        this.apiKey     = appProperties.getAi().getGemini().getApiKey();
        this.videoModel = appProperties.getContent().getVideoModel();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key tanımsız; Veo video üretimi devre dışı.");
            return;
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(30_000);

        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("x-goog-api-key", apiKey)
                .requestFactory(factory)
                .build();

        log.info("Veo video service hazır: model={}, aspectRatio={}, duration={}s",
                videoModel, ASPECT_RATIO, DURATION_SECS);
    }

    public boolean isActive() {
        return restClient != null;
    }

    /**
     * Prompt'a göre 9:16 dikey, 8 saniyelik Reel videosu üretir.
     *
     * @return MP4 byte dizisi; hata veya servis pasif ise null
     */
    public byte[] generateVideo(String prompt) {
        if (restClient == null) {
            log.info("Veo service aktif değil; video üretimi atlandı.");
            return null;
        }
        try {
            Map<String, Object> body = Map.of(
                    "instances",  List.of(Map.of("prompt", prompt)),
                    "parameters", Map.of(
                            "aspectRatio",     ASPECT_RATIO,
                            "resolution",      RESOLUTION,
                            "durationSeconds", DURATION_SECS
                    )
            );

            String createJson = restClient.post()
                    .uri("/models/" + videoModel + ":predictLongRunning")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(createJson);
            String operationName = root.path("name").asText(null);

            if (operationName == null || operationName.isBlank()) {
                log.warn("Veo: operation name bulunamadı. Yanıt: {}",
                        createJson.substring(0, Math.min(500, createJson.length())));
                return null;
            }

            log.info("Veo video işlemi başladı: {}", operationName);
            return pollAndDownload(operationName);

        } catch (RestClientResponseException ex) {
            log.error("Veo API HTTP hatası: status={}, body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            return null;
        } catch (Exception ex) {
            log.error("Veo video üretimi başarısız: {}", ex.getMessage(), ex);
            return null;
        }
    }

    // ============================================================
    // Polling
    // ============================================================

    private byte[] pollAndDownload(String operationName) throws Exception {
        // operationName = "operations/abc123" → /v1beta/operations/abc123
        String pollUri = operationName.startsWith("/") ? operationName : "/" + operationName;

        for (int i = 0; i < MAX_POLL; i++) {
            Thread.sleep(POLL_DELAY_MS);

            String statusJson = restClient.get()
                    .uri(pollUri)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(statusJson);
            boolean done = root.path("done").asBoolean(false);

            log.debug("Veo polling: op={}, done={}, attempt={}/{}", operationName, done, i + 1, MAX_POLL);

            if (done) {
                if (!root.path("error").isMissingNode()) {
                    log.error("Veo operation hata: {}", root.path("error"));
                    return null;
                }
                return extractAndDownload(root);
            }
        }
        log.error("Veo polling zaman aşımı: {}", operationName);
        return null;
    }

    // ============================================================
    // Video çıkar + indir
    // ============================================================

    private byte[] extractAndDownload(JsonNode root) throws Exception {
        // response.generateVideoResponse.generatedSamples[0].video.uri
        JsonNode response = root.path("response");
        JsonNode samples  = response.path("generateVideoResponse").path("generatedSamples");
        if (samples.isMissingNode() || samples.isEmpty()) {
            // Bazı API versiyonlarında doğrudan response altında olabilir
            samples = response.path("generatedSamples");
        }

        if (samples.isMissingNode() || samples.isEmpty()) {
            log.warn("Veo: generatedSamples bulunamadı. response: {}", response.toString().substring(0, Math.min(300, response.toString().length())));
            return null;
        }

        String videoUri = samples.get(0).path("video").path("uri").asText(null);
        if (videoUri == null || videoUri.isBlank()) {
            log.warn("Veo: video URI bulunamadı. sample[0]: {}", samples.get(0));
            return null;
        }

        log.info("Veo video URI: {}", videoUri);

        // SimpleClientHttpRequestFactory redirect'te x-goog-api-key header'ını strip eder
        // (Cloud Storage'a yönlendirme). key= query param redirect'te korunur — her ikisini ekle.
        String sep = videoUri.contains("?") ? "&" : "?";
        String downloadUrl = videoUri;
        if (!downloadUrl.contains("alt=media")) {
            downloadUrl += sep + "alt=media";
            sep = "&";
        }
        if (!downloadUrl.contains("key=")) {
            downloadUrl += sep + "key=" + apiKey;
        }

        log.info("Veo indirme başlıyor: {}", downloadUrl.replaceAll("key=[^&]+", "key=***"));

        byte[] bytes = RestClient.builder()
                .defaultHeader("x-goog-api-key", apiKey)
                .build()
                .get()
                .uri(downloadUrl)
                .retrieve()
                .body(byte[].class);

        if (bytes != null && bytes.length < 10_000) {
            // Küçük yanıt → muhtemelen hata JSON'u (video MB seviyesinde olmalı)
            log.warn("Veo: indirilen boyut çok küçük ({} bytes) — hata yanıtı olabilir: {}",
                    bytes.length, new String(bytes, StandardCharsets.UTF_8));
            return null;
        }

        log.info("Veo video indirildi: {} bytes", bytes == null ? 0 : bytes.length);
        return bytes;
    }
}
