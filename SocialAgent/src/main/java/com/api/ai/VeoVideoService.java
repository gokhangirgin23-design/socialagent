package com.api.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
     * Prompt ve opsiyonel ürün görseline göre 9:16 dikey Reel videosu üretir.
     *
     * @param productImageUrl data URL veya HTTP URL (null olabilir; varsa Veo'ya referans görsel gönderilir)
     * @return MP4 byte dizisi; hata veya servis pasif ise null
     */
    public byte[] generateVideo(String prompt, String productImageUrl) {
        if (restClient == null) {
            log.info("Veo service aktif değil; video üretimi atlandı.");
            return null;
        }
        try {
            Map<String, Object> instance = buildInstance(prompt, productImageUrl);

            Map<String, Object> body = Map.of(
                    "instances",  List.of(instance),
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
    // Instance builder (prompt + opsiyonel ürün görseli)
    // ============================================================

    private Map<String, Object> buildInstance(String prompt, String productImageUrl) {
        Map<String, Object> instance = new HashMap<>();
        instance.put("prompt", prompt);

        if (productImageUrl == null || productImageUrl.isBlank()) {
            return instance;
        }
        try {
            String base64;
            String mimeType = "image/jpeg";

            if (productImageUrl.startsWith("data:")) {
                // data:[mimeType];base64,[data]
                int commaIdx = productImageUrl.indexOf(',');
                String header = productImageUrl.substring(5, commaIdx); // "image/jpeg;base64"
                mimeType = header.split(";")[0];
                base64 = productImageUrl.substring(commaIdx + 1);
            } else {
                // HTTP / S3 URL — indir ve encode et
                HttpClient dlClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();
                HttpResponse<byte[]> dlResp = dlClient.send(
                        HttpRequest.newBuilder().uri(URI.create(productImageUrl)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (dlResp.statusCode() != 200) {
                    log.warn("Veo: ürün görseli indirilemedi (HTTP {}); görsel olmadan devam.", dlResp.statusCode());
                    return instance;
                }
                base64 = Base64.getEncoder().encodeToString(dlResp.body());
                if (productImageUrl.toLowerCase(Locale.ROOT).contains(".png")) mimeType = "image/png";
                else if (productImageUrl.toLowerCase(Locale.ROOT).contains(".webp")) mimeType = "image/webp";
            }

            instance.put("image", Map.of("bytesBase64Encoded", base64, "mimeType", mimeType));
            log.info("Veo: ürün görseli referans olarak eklendi (mimeType={})", mimeType);
        } catch (Exception ex) {
            log.warn("Veo: ürün görseli eklenemedi (görsel olmadan devam): {}", ex.getMessage());
        }
        return instance;
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
            // RAI (Responsible AI) filtre kontrolü
            JsonNode gvr = response.path("generateVideoResponse");
            int filtered = gvr.path("raiMediaFilteredCount").asInt(0);
            if (filtered > 0) {
                JsonNode reasons = gvr.path("raiMediaFilteredReasons");
                log.warn("Veo: video Responsible AI filtresi tarafından engellendi ({}). Neden: {}",
                        filtered, reasons);
            } else {
                log.warn("Veo: generatedSamples bulunamadı. response: {}",
                        response.toString().substring(0, Math.min(400, response.toString().length())));
            }
            return null;
        }

        String videoUri = samples.get(0).path("video").path("uri").asText(null);
        if (videoUri == null || videoUri.isBlank()) {
            log.warn("Veo: video URI bulunamadı. sample[0]: {}", samples.get(0));
            return null;
        }

        log.info("Veo video URI: {}", videoUri);

        // Veo URI formatı: "files/{id}:download?alt=media"
        // :download endpoint'i GCS pre-signed URL'e 302 redirect yapar.
        // Pre-signed URL kendi kendine auth'lu — x-goog-api-key header'ı GCS'e GÖNDERİLMEMELİ.
        String fileBase = videoUri.replaceAll(":download.*$", "").replaceAll("\\?.*$", "");
        String step1Url = fileBase + ":download?alt=media&key=" + apiKey;

        log.info("Veo indirme adım-1: {}", step1Url.replaceAll("key=[^&]+", "key=***"));

        // NEVER redirect: 302 Location header'ını elle okuyup GCS'e ayrı, temiz istek yapacağız
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest step1Request = HttpRequest.newBuilder()
                .uri(URI.create(step1Url))
                .header("x-goog-api-key", apiKey)
                .GET()
                .build();

        HttpResponse<byte[]> step1Response = httpClient.send(step1Request,
                HttpResponse.BodyHandlers.ofByteArray());

        int status1 = step1Response.statusCode();

        if (status1 == 302 || status1 == 301) {
            // GCS pre-signed URL'i al
            String gcsUrl = step1Response.headers().firstValue("location")
                    .or(() -> step1Response.headers().firstValue("Location"))
                    .orElse(null);

            if (gcsUrl == null || gcsUrl.isBlank()) {
                log.warn("Veo: {} redirect ama Location header yok. Body: {}",
                        status1, new String(step1Response.body(), StandardCharsets.UTF_8));
                return null;
            }

            log.info("Veo indirme adım-2 (GCS): {}", gcsUrl.substring(0, Math.min(80, gcsUrl.length())));

            // GCS pre-signed URL: auth header olmadan indir
            HttpRequest step2Request = HttpRequest.newBuilder()
                    .uri(URI.create(gcsUrl))
                    .GET()
                    .build();

            HttpResponse<byte[]> step2Response = httpClient.send(step2Request,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (step2Response.statusCode() != 200) {
                log.warn("Veo GCS indirme HTTP {}: {}", step2Response.statusCode(),
                        new String(step2Response.body(), StandardCharsets.UTF_8).substring(0, Math.min(300,
                                step2Response.body().length)));
                return null;
            }

            byte[] bytes = step2Response.body();
            if (bytes.length < 10_000) {
                log.warn("Veo: GCS indirilen boyut çok küçük ({} bytes): {}", bytes.length,
                        new String(bytes, StandardCharsets.UTF_8));
                return null;
            }

            log.info("Veo video indirildi: {} bytes", bytes.length);
            return bytes;

        } else if (status1 == 200) {
            // Redirect yok, direkt içerik
            byte[] bytes = step1Response.body();
            if (bytes.length < 10_000) {
                log.warn("Veo: HTTP 200 ama küçük yanıt ({} bytes): {}", bytes.length,
                        new String(bytes, StandardCharsets.UTF_8));
                return null;
            }
            log.info("Veo video indirildi (direkt): {} bytes", bytes.length);
            return bytes;

        } else {
            log.warn("Veo indirme HTTP {}: {}", status1,
                    new String(step1Response.body(), StandardCharsets.UTF_8));
            return null;
        }
    }
}
