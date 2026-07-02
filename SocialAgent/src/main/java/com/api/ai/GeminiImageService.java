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
import org.springframework.web.client.RestClientResponseException;

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
        // Bug fix: OpenAI ile AYNI property paylaşılıyordu (gpt-image-1.5 -> 404); artık kendi property'si var
        this.imageModel = appProperties.getContent().getGeminiImageModel();

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
    public boolean isActive() {
        return restClient != null;
    }

    public byte[] generateImage(String textPrompt, String productImageUrl) {
        if (restClient == null) {
            log.info("Gemini image service aktif değil (API key tanımlı değil); görsel üretim atlandı.");
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

            log.debug("Gemini image yanıtı: uzunluk={}", responseJson == null ? 0 : responseJson.length());
            return extractImageBytes(responseJson);
        } catch (RestClientResponseException ex) {
            log.error("Gemini API HTTP hatası: status={}, model={}, body={}",
                    ex.getStatusCode(), imageModel, ex.getResponseBodyAsString());
            return null;
        } catch (Exception ex) {
            log.error("Gemini görsel üretimi başarısız: model={}, hata={}", imageModel, ex.getMessage(), ex);
            return null;
        }
    }

    private Map<String, Object> buildRequestBody(String textPrompt, String productImageUrl) throws IOException {
        List<Map<String, Object>> parts = new ArrayList<>();

        // Ürün görseli varsa modele gönder (base64 inline — Gemini REST API camelCase gerektirir)
        if (productImageUrl != null && !productImageUrl.isBlank()) {
            try {
                if (productImageUrl.startsWith("data:")) {
                    // Frontend'den gelen data URI: data:image/jpeg;base64,/9j/...
                    int comma = productImageUrl.indexOf(',');
                    String header = productImageUrl.substring(5, comma);   // image/jpeg;base64
                    String mimeType = header.split(";")[0];                // image/jpeg
                    String base64 = productImageUrl.substring(comma + 1);
                    parts.add(Map.of("inlineData", Map.of("mimeType", mimeType, "data", base64)));
                } else {
                    byte[] imageBytes = downloadBytes(productImageUrl);
                    String base64 = Base64.getEncoder().encodeToString(imageBytes);
                    String mimeType = detectMimeType(productImageUrl);
                    parts.add(Map.of("inlineData", Map.of("mimeType", mimeType, "data", base64)));
                }
            } catch (Exception ex) {
                log.warn("Ürün görseli işlenemedi, atlanıyor: hata={}", ex.getMessage());
            }
        }

        parts.add(Map.of("text", textPrompt));

        // responseModalities: TEXT+IMAGE — sadece IMAGE bazı model versiyonlarında hata verebilir
        return Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", Map.of("responseModalities", List.of("TEXT", "IMAGE"))
        );
    }

    private byte[] extractImageBytes(String responseJson) throws Exception {
        if (responseJson == null || responseJson.isBlank()) {
            log.warn("Gemini boş yanıt döndü.");
            return null;
        }
        JsonNode root = objectMapper.readTree(responseJson);

        // Hata objesi varsa logla
        if (!root.path("error").isMissingNode()) {
            log.error("Gemini yanıtında hata nesnesi: {}", root.path("error").toString());
            return null;
        }

        JsonNode candidates = root.path("candidates");
        if (candidates.isEmpty()) {
            log.warn("Gemini yanıtında 'candidates' yok. Yanıt: {}",
                    responseJson.substring(0, Math.min(500, responseJson.length())));
            return null;
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        for (JsonNode part : parts) {
            JsonNode inlineData = part.path("inlineData");
            if (!inlineData.isMissingNode()) {
                String base64Data = inlineData.path("data").asText();
                log.info("Gemini görsel verisi alındı: {} bytes (base64)", base64Data.length());
                return Base64.getDecoder().decode(base64Data);
            }
        }
        // Parts var ama image yok — text-only yanıt döndü
        log.warn("Gemini görsel verisi bulunamadı; parts={}",
                responseJson.substring(0, Math.min(500, responseJson.length())));
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
