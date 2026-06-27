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
        // S3 yapılandırılmadıysa base64 data URL olarak sakla (geliştirme ortamı fallback)
        if (s3Client == null) {
            log.info("S3 kapalı; görsel base64 data URL olarak saklanıyor: contentRequestId={}, index={}",
                    contentRequestId, index);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);
        }
        try {
            String key = "content/%s/%s/image_%d.png".formatted(userId, contentRequestId, index);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("image/png")
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(imageBytes));
            String url = "https://%s.s3.%s.amazonaws.com/%s".formatted(bucket, region, key);
            log.info("Görsel S3'e yüklendi: key={}", key);
            return url;
        } catch (Exception ex) {
            log.error("S3 yükleme başarısız: contentRequestId={}, index={}, hata={}",
                    contentRequestId, index, ex.getMessage());
            return null;
        }
    }
}
