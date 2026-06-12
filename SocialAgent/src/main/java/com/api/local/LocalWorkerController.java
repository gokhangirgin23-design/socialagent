package com.api.local;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.common.DataResponse;
import com.api.common.ResponseCode;

import lombok.RequiredArgsConstructor;

/**
 * LOCAL profil iç-test uçları (sadece local profilde oluşur — @Profile("local")).
 * Tüm uçlar POST (CLAUDE.md Madde 2) ve HTTP 200 + responseCode (Madde 3) konvansiyonuna uyar.
 *
 * Bu controller RabbitMQ'yu devre dışı bırakır: kuyruğu dinlemek yerine LocalJobRunner ile
 * report_request tablosundan FIFO istek seçip gerçek pipeline'ı çalıştırır.
 *
 * Güvenlik: SecurityConfig'te /local/** permitAll yapıldı (controller zaten yalnızca local'de var).
 * userId istekten alınmaz; pipeline istek kaydındaki user_id'yi kullanır.
 */
@RestController
@RequestMapping("/local")
@Profile("local")
@RequiredArgsConstructor
public class LocalWorkerController {

    // RabbitMQ'suz istek çalıştırıcı
    private final LocalJobRunner localJobRunner;

    /**
     * Sıradaki (en eski) bekleyen rapor isteğini işler.
     * Bekleyen istek yoksa responseCode = NOT_FOUND.
     */
    @PostMapping("/run-next-job")
    public DataResponse<UUID> runNextJob() {
        UUID requestId = localJobRunner.runNextRequest();
        if (requestId == null) {
            return DataResponse.of(ResponseCode.NOT_FOUND);
        }
        return DataResponse.success(requestId);
    }

    /**
     * Body ile verilen belirli bir rapor isteğini işler (FIFO sırasına bakmaz).
     */
    @PostMapping("/run-job")
    public DataResponse<UUID> runJob(@RequestBody RunJobRequest request) {
        if (request == null || request.requestId() == null) {
            return DataResponse.of(ResponseCode.VALIDATION_ERROR);
        }
        localJobRunner.runRequest(request.requestId());
        return DataResponse.success(request.requestId());
    }

    /**
     * Bekleyen tüm rapor isteklerini FIFO sırayla işler (güvenlik tavanı LocalJobRunner.MAX_BATCH).
     */
    @PostMapping("/run-all-pending")
    public DataResponse<List<UUID>> runAllPending() {
        return DataResponse.success(localJobRunner.runAllPending());
    }

    /**
     * Bekleyen rapor isteklerini FIFO sırasıyla listeler (işlemeden).
     */
    @PostMapping("/pending")
    public DataResponse<List<LocalJobRunner.PendingRequest>> pending() {
        return DataResponse.success(localJobRunner.listPending());
    }

    /**
     * /local/run-job için basit istek gövdesi.
     *
     * @param requestId işlenecek rapor isteği id'si
     */
    public record RunJobRequest(UUID requestId) {
    }
}
