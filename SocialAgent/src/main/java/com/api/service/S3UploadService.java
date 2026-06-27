package com.api.service;

import java.time.Duration;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

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
    private S3Presigner s3Presigner;
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

        StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));
        Region awsRegion = Region.of(region);

        this.s3Client = S3Client.builder()
                .region(awsRegion)
                .credentialsProvider(creds)
                .build();

        this.s3Presigner = S3Presigner.builder()
                .region(awsRegion)
                .credentialsProvider(creds)
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
        if (imageBytes == null) return null;
        if (s3Client == null) {
            log.warn("S3 kapalı; görsel yüklenemedi: contentRequestId={}, index={}", contentRequestId, index);
            return null;
        }
        String mimeType = detectMimeType(imageBytes);
        String ext = mimeType.equals("image/jpeg") ? "jpg" : mimeType.equals("image/webp") ? "webp" : "png";
        String key = "content/%s/%s/image_%d.%s".formatted(userId, contentRequestId, index, ext);
        try {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(mimeType).build(),
                    RequestBody.fromBytes(imageBytes));
            String s3Url = "https://%s.s3.%s.amazonaws.com/%s".formatted(bucket, region, key);
            log.info("Görsel S3'e yüklendi: key={}", key);
            return s3Url;
        } catch (Exception ex) {
            log.error("S3 yükleme başarısız: contentRequestId={}, index={}, hata={}", contentRequestId, index, ex.getMessage());
            return null;
        }
    }

    /**
     * MP4 video byte dizisini S3'e yükler ve URL döner.
     */
    public String uploadVideo(byte[] videoBytes, UUID userId, UUID contentRequestId) {
        if (videoBytes == null) return null;
        if (s3Client == null) {
            log.warn("S3 kapalı; video yüklenemedi: contentRequestId={}", contentRequestId);
            return null;
        }
        String key = "content/%s/%s/video_0.mp4".formatted(userId, contentRequestId);
        try {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType("video/mp4").build(),
                    RequestBody.fromBytes(videoBytes));
            String s3Url = "https://%s.s3.%s.amazonaws.com/%s".formatted(bucket, region, key);
            log.info("Video S3'e yüklendi: key={}, {} bytes", key, videoBytes.length);
            return s3Url;
        } catch (Exception ex) {
            log.error("S3 video yükleme başarısız: contentRequestId={}, hata={}", contentRequestId, ex.getMessage());
            return null;
        }
    }

    /** S3 URL'ini 1 saatlik pre-signed URL'e çevirir. data: URL ise dokunmaz. */
    public String presign(String s3Url) {
        if (s3Presigner == null || s3Url == null || s3Url.startsWith("data:")) return s3Url;
        String prefix = "https://" + bucket + ".s3." + region + ".amazonaws.com/";
        if (!s3Url.startsWith(prefix)) return s3Url;
        String key = s3Url.substring(prefix.length());
        try {
            return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(r -> r.bucket(bucket).key(key))
                    .build()).url().toString();
        } catch (Exception ex) {
            log.warn("Pre-signed URL oluşturulamadı: key={}, hata={}", key, ex.getMessage());
            return s3Url;
        }
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 4) {
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50) return "image/png";
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46
                    && bytes.length >= 12 && bytes[8] == 0x57 && bytes[9] == 0x45) return "image/webp";
        }
        return "image/png";
    }
}
