package com.api.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.api.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI gpt-image-1 ile görsel üretimi.
 * Ürün görseli varsa /images/edits (multipart) kullanır; yoksa /images/generations (JSON).
 * Yanıt her zaman data[0].b64_json formatındadır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiImageService {

    private static final String BASE_URL = "https://api.openai.com/v1";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestClient restClient;
    private String imageModel;

    @PostConstruct
    void init() {
        String apiKey = appProperties.getAi().getOpenai().getApiKey();
        this.imageModel = appProperties.getContent().getImageModel();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI API key tanımsız; görsel üretimi devre dışı.");
            return;
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(120_000);

        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();

        log.info("OpenAI image service hazır: model={}", imageModel);
    }

    public boolean isActive() { return restClient != null; }

    /**
     * @param size "1024x1024" | "1024x1536" | "1536x1024"
     */
    public byte[] generateImage(String prompt, String productImageUrl, String size) {
        if (restClient == null) return null;
        try {
            if (productImageUrl != null && !productImageUrl.isBlank()) {
                return generateWithReference(prompt, productImageUrl, size);
            }
            return generateTextToImage(prompt, size);
        } catch (RestClientResponseException ex) {
            log.error("OpenAI image API hatası: status={}, body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return null;
        } catch (Exception ex) {
            log.error("OpenAI image üretimi başarısız: {}", ex.getMessage(), ex);
            return null;
        }
    }

    // ============================================================
    // Text-to-image
    // ============================================================

    private byte[] generateTextToImage(String prompt, String size) throws Exception {
        Map<String, Object> body = Map.of(
                "model",   imageModel,
                "prompt",  prompt,
                "n",       1,
                "size",    size,
                "quality", "high"
        );

        String json = restClient.post()
                .uri("/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        return extractB64(json);
    }

    // ============================================================
    // Image edit (ürün görseli referans)
    // ============================================================

    private byte[] generateWithReference(String prompt, String productImageUrl, String size) throws Exception {
        byte[] imgBytes = loadImageBytes(productImageUrl);
        if (imgBytes == null) {
            log.warn("OpenAI: ürün görseli yüklenemedi; text-to-image ile devam ediliyor");
            return generateTextToImage(prompt, size);
        }

        ByteArrayResource imgResource = new ByteArrayResource(imgBytes) {
            @Override public String getFilename() { return "product.png"; }
        };
        HttpHeaders imgHeaders = new HttpHeaders();
        imgHeaders.setContentType(MediaType.IMAGE_PNG);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("model",   imageModel);
        form.add("prompt",  prompt);
        form.add("n",       "1");
        form.add("size",    size);
        form.add("quality", "high");
        form.add("image",   new HttpEntity<>(imgResource, imgHeaders));

        String json = restClient.post()
                .uri("/images/edits")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);

        return extractB64(json);
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    private byte[] loadImageBytes(String url) {
        try {
            if (url.startsWith("data:")) {
                int commaIdx = url.indexOf(',');
                return Base64.getDecoder().decode(url.substring(commaIdx + 1));
            }
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<byte[]> resp = client.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception ex) {
            log.warn("OpenAI: görsel yükleme hatası: {}", ex.getMessage());
            return null;
        }
    }

    private byte[] extractB64(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        String b64 = root.path("data").path(0).path("b64_json").asText(null);
        if (b64 == null || b64.isBlank()) {
            log.warn("OpenAI: b64_json bulunamadı. Yanıt: {}",
                    json.substring(0, Math.min(300, json.length())));
            return null;
        }
        return Base64.getDecoder().decode(b64);
    }
}
