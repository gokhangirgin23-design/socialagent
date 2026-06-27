package com.api.service;

import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.api.config.AppProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * AWS S3 görsel yükleme servisi.
 * GeminiImageService'ten dönen PNG byte'larını S3'e yükler ve public URL döner.
 *
 * AWS key'leri tanımlı değilse init'te client kurulmaz; upload çağrıları null döner.
 * Pipeline null URL ile devam eder (visual_urls alanı boş kalır).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3UploadService {

    private final AppProperties appProperties;

    private S3Client s3Client;
    private String bucket;
    private String region;

    @PostConstruct
    void init() {
        AppProperties.Aws aws = appProperties.getAws();
        this.bucket = aws.getBucket();
        this.region = aws.getRegion();

        String accessKey = aws.getAccessKeyId();
        String secretKey = aws.getSecretAccessKey();

        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.warn("AWS credentials tanımlı değil; S3 yükleme devre dışı.");
            return;
        }

        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();

        log.info("S3 client hazır: bucket={}, region={}", bucket, region);
    }

    /**
     * PNG byte dizisini S3'e yükler ve public URL döner.
     * Client kurulmadıysa null döner.
     *
     * @param imageBytes     PNG verisi
     * @param userId         klasör ayrımı için kullanıcı id'si
     * @param contentRequestId klasör ayrımı için istek id'si
     * @param index          carousel slayt numarası (tek görsel için 0)
     * @return "https://{bucket}.s3.{region}.amazonaws.com/{key}" veya null
     */
    public String upload(byte[] imageBytes, UUID userId, UUID contentRequestId, int index) {
        if (imageBytes == null) {
            return null;
        }
        // Gerçek MIME type'ı byte magic number'dan tespit et
        String mimeType = detectMimeType(imageBytes);
        String ext = mimeType.equals("image/jpeg") ? "jpg" : mimeType.equals("image/webp") ? "webp" : "png";

        // DB'ye her zaman data URL kaydet — tarayıcı S3 erişim izni olmadan da görebilsin
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        // S3 varsa yedek olarak yükle (CDN/arşiv amaçlı)
        if (s3Client != null) {
            try {
                String key = "content/%s/%s/image_%d.%s".formatted(userId, contentRequestId, index, ext);
                s3Client.putObject(
                        PutObjectRequest.builder().bucket(bucket).key(key).contentType(mimeType).build(),
                        RequestBody.fromBytes(imageBytes));
                log.info("Görsel S3'e yedeklendi: key={}, mimeType={}", key, mimeType);
            } catch (Exception ex) {
                log.warn("S3 yedekleme başarısız (data URL kullanılacak): contentRequestId={}, hata={}",
                        contentRequestId, ex.getMessage());
            }
        }

        return dataUrl;
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 4) {
            // PNG: 89 50 4E 47
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50) return "image/png";
            // JPEG: FF D8
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "image/jpeg";
            // WEBP: RIFF....WEBP
            if (bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46
                    && bytes.length >= 12 && bytes[8] == 0x57 && bytes[9] == 0x45) return "image/webp";
        }
        return "image/png";
    }
}
