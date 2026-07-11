package com.api.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.dto.repository.UserFreeUsageRepository;
import com.api.entity.ContentType;
import com.api.entity.UserFreeUsage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ücretsiz ilk kullanım hakkı (V11 migration): kullanıcı başına 1 rapor + (o rapora sıralı bağlı)
 * 1 post/story hakkı. Kredi sistemine (PaymentService/user_payment) hiç dokunmaz — kontrol ve
 * kayıt tamamen user_free_usage tablosundan yapılır. Service interface yok (CLAUDE.md Madde 1).
 *
 * Rapor tarafı: report_request'in active_lock_key UNIQUE kısıtı (E7 fix) kullanıcı başına
 * eşzamanlı tek istek garanti ettiğinden, rapor hakkının tüketimi burada basit (koşulsuz)
 * bir UPDATE ile yapılır — yarış riski yoktur.
 *
 * İçerik tarafı: content_request'te böyle bir kilit YOK, bu yüzden içerik hakkının tüketimi
 * atomik/koşullu bir UPDATE (WHERE free_content_used = 0) ile yapılır — çift-tık aynı anda 2
 * istek gönderse bile yalnızca biri hakkı gerçekten tüketir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FreeUsageService {

    private final JdbcTemplate jdbcTemplate;
    private final UserFreeUsageRepository repository;

    /**
     * Yeni kullanıcı kaydında (AuthService) çağrılır; satır zaten varsa dokunmaz.
     * V11 migration'ı mevcut tüm kullanıcılar için satır seed ettiğinden bu yalnızca
     * BUNDAN SONRA kayıt olacak kullanıcılar için devreye girer.
     */
    @Transactional
    public void ensureRowExists(UUID userId) {
        if (repository.existsById(userId)) {
            return;
        }
        UserFreeUsage row = new UserFreeUsage();
        row.setUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedDate(now);
        row.setUpdatedDate(now);
        repository.save(row);
    }

    /** Kullanıcının hâlâ ücretsiz rapor hakkı var mı? */
    public boolean isFreeReportAvailable(UUID userId) {
        UserFreeUsage row = repository.findById(userId).orElse(null);
        return row != null && (row.getFreeReportUsed() == null || row.getFreeReportUsed() == 0);
    }

    /**
     * Ücretsiz rapor hakkını tüketir (koşulsuz UPDATE — bkz. sınıf yorumu, active_lock_key
     * zaten yarışı engeller). requestId: bu ücretsiz raporun report_request.request_id'si;
     * içerik hakkının hangi rapora bağlı olduğunu belirlemek için saklanır.
     */
    @Transactional
    public void markFreeReportUsed(UUID userId, UUID requestId) {
        jdbcTemplate.update("""
                UPDATE user_free_usage
                SET free_report_used = 1, free_report_request_id = ?, free_report_used_date = ?, updated_date = ?
                WHERE user_id = ?
                """, requestId, LocalDateTime.now(), LocalDateTime.now(), userId);
        log.info("Ücretsiz ilk rapor hakkı tüketildi: userId={}, requestId={}", userId, requestId);
    }

    /**
     * Ücretsiz içerik (post/story) hakkı bu reportId (report.report_id) için kullanılabilir mi?
     * SADECE ücretsiz üretilen raporun kendisinden, SADECE POST/STORY için (Carousel/Reel hariç).
     */
    public boolean isFreeContentAvailable(UUID userId, UUID reportId, ContentType contentType) {
        if (contentType != ContentType.POST && contentType != ContentType.STORY) {
            return false;
        }
        UserFreeUsage row = repository.findById(userId).orElse(null);
        if (row == null || row.getFreeReportRequestId() == null) {
            return false;
        }
        if (row.getFreeContentUsed() != null && row.getFreeContentUsed() == 1) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM report
                WHERE report_id = ? AND request_id = ?
                """, Integer.class, reportId, row.getFreeReportRequestId());
        return count != null && count > 0;
    }

    /**
     * Ücretsiz içerik hakkını ATOMİK olarak tüketmeyi dener (bkz. sınıf yorumu — content_request'te
     * duplicate-guard kilidi yok, bu yüzden koşullu UPDATE ile yarış engellenir).
     * @return true ise hak bu çağrıda gerçekten tüketildi; false ise (yarışta kaybetti veya
     *         zaten kullanılmıştı) çağıran normal kredi akışına dönmelidir.
     */
    @Transactional
    public boolean tryConsumeFreeContent(UUID userId, UUID contentRequestId) {
        int affected = jdbcTemplate.update("""
                UPDATE user_free_usage
                SET free_content_used = 1, free_content_id = ?, free_content_used_date = ?, updated_date = ?
                WHERE user_id = ? AND (free_content_used = 0 OR free_content_used IS NULL)
                """, contentRequestId, LocalDateTime.now(), LocalDateTime.now(), userId);
        boolean consumed = affected > 0;
        if (consumed) {
            log.info("Ücretsiz ilk içerik hakkı tüketildi: userId={}, contentRequestId={}", userId, contentRequestId);
        }
        return consumed;
    }

    /** Kullanıcı daha önce hiç kredi paketi satın almış mı? (Carousel'in serbest bırakılması için.) */
    public boolean hasEverPurchased(UUID userId) {
        // Cüzdan henüz oluşmamış olabilir (ensureWallet ilk kredi işleminde çağrılır) — queryForObject
        // bu durumda EmptyResultDataAccessException fırlatır, bu yüzden liste dönen query() kullanılır.
        java.util.List<Long> rows = jdbcTemplate.query(
                "SELECT total_credit_topup FROM user_payment WHERE user_id = ?",
                (rs, i) -> rs.getObject("total_credit_topup", Long.class), userId);
        return !rows.isEmpty() && rows.get(0) != null && rows.get(0) > 0;
    }
}
