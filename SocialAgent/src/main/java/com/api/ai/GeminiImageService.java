package com.api.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.api.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gemini görsel üretim servisi.
 * Gemini image generation REST API'sini doğrudan çağırır (LangChain4j görsel üretim
 * çıktısını desteklemediğinden bu servis kendi RestClient'ını yönetir).
 *
 * API uç noktası: POST /v1beta/models/{model}:generateContent?key={apiKey}
 * responseModalities: ["IMAGE"] → base64 PNG döner.
 *
 * API key tanımlı değilse tüm çağrılar null döner; pipeline çökmez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiImageService {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestClient restClient;
    private String apiKey;
    private String imageModel;

    @PostConstruct
    void init() {
        this.apiKey = appProperties.getAi().getGemini().getApiKey();
        this.imageModel = appProperties.getContent().getImageModel();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key tanımsız; görsel üretim devre dışı.");
            return;
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        long timeoutMs = appProperties.getAi().getGemini().getTimeoutSeconds() * 1000L;
        factory.setConnectTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        factory.setReadTimeout((int) Math.min(timeoutMs * 2, Integer.MAX_VALUE));

        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                .build();

        log.info("Gemini image service hazır: model={}", imageModel);
    }

    /**
     * Verilen prompt'u görsel üreterek base64 PNG byte dizisi olarak döner.
     * productImageUrl varsa ürün görseli de modele gönderilir.
     *
     * @param textPrompt     görsel üretim prompt'u
     * @param productImageUrl ürün görseli URL'i (null olabilir)
     * @return PNG byte dizisi; hata veya model yoksa null
     */
    public byte[] generateImage(String textPrompt, String productImageUrl) {
        if (restClient == null) {
            log.debug("Gemini image service aktif değil; görsel üretim atlandı.");
            return null;
        }
        try {
            Map<String, Object> requestBody = buildRequestBody(textPrompt, productImageUrl);
            String endpoint = "/models/" + imageModel + ":generateContent?key=" + apiKey;

            String responseJson = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return extractImageBytes(responseJson);
        } catch (Exception ex) {
            log.error("Gemini görsel üretimi başarısız: model={}, hata={}", imageModel, ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildRequestBody(String textPrompt, String productImageUrl) throws IOException {
        List<Map<String, Object>> parts = new ArrayList<>();

        // Ürün görseli varsa modele gönder (base64 inline)
        if (productImageUrl != null && !productImageUrl.isBlank()) {
            try {
                if (productImageUrl.startsWith("data:")) {
                    // Frontend'den gelen data URI: data:image/jpeg;base64,/9j/...
                    int comma = productImageUrl.indexOf(',');
                    String header = productImageUrl.substring(5, comma);         // image/jpeg;base64
                    String mimeType = header.split(";")[0];                      // image/jpeg
                    String base64 = productImageUrl.substring(comma + 1);
                    parts.add(Map.of("inline_data", Map.of("mime_type", mimeType, "data", base64)));
                } else {
                    byte[] imageBytes = downloadBytes(productImageUrl);
                    String base64 = Base64.getEncoder().encodeToString(imageBytes);
                    String mimeType = detectMimeType(productImageUrl);
                    parts.add(Map.of("inline_data", Map.of("mime_type", mimeType, "data", base64)));
                }
            } catch (Exception ex) {
                log.warn("Ürün görseli işlenemedi, atlanıyor: hata={}", ex.getMessage());
            }
        }

        parts.add(Map.of("text", textPrompt));

        return Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", Map.of("responseModalities", List.of("IMAGE"))
        );
    }

    private byte[] extractImageBytes(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode candidates = root.path("candidates");
        if (candidates.isEmpty()) {
            log.warn("Gemini yanıtında 'candidates' yok.");
            return null;
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        for (JsonNode part : parts) {
            JsonNode inlineData = part.path("inlineData");
            if (!inlineData.isMissingNode()) {
                String base64Data = inlineData.path("data").asText();
                return Base64.getDecoder().decode(base64Data);
            }
        }
        log.warn("Gemini yanıtında görsel verisi bulunamadı.");
        return null;
    }

    private byte[] downloadBytes(String urlStr) throws IOException {
        try (InputStream is = new URL(urlStr).openStream()) {
            return is.readAllBytes();
        }
    }

    private String detectMimeType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".gif")) return "image/gif";
        if (lower.contains(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
