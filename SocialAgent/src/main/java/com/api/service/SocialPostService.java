package com.api.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.apify.ApifyPost;
import com.api.dto.repository.SocialPostRepository;
import com.api.entity.SocialPost;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * social_post yazma + tekrar-analiz koruması iş mantığı (CLAUDE.md Bölüm 10).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Save-or-update: UNIQUE(platform, platform_post_id) var ise etkileşim metrikleri güncellenir.
 * Tekrar-analiz koruması: post_analysis JOIN ile hesap son DEFAULT_ANALYSIS_PERIOD_DAYS gün içinde
 * analiz edildiyse Apify + AI atlanır.
 * Join'ler eski stil "=" + JdbcTemplate native (CLAUDE.md Madde 6); insert JPA save.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialPostService {

    // Native sorgular için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // social_post insert için JPA repository
    private final SocialPostRepository socialPostRepository;

    // Tekrar-analiz penceresi (gün); sabit varsayılan — per-request yapılandırma yok
    private static final int DEFAULT_ANALYSIS_PERIOD_DAYS = 3;

    /**
     * Hedef hesap son DEFAULT_ANALYSIS_PERIOD_DAYS gün içinde analiz edildi mi? (tekrar-analiz koruması).
     * Kimlik hedef tipine göre seçilir:
     *  - MONITORED: monitored_account_id ile post_analysis tarihi kontrol edilir
     *  - OWN:       report_request.selected_user_social_account_id üzerinden kontrol
     *  - SECTOR:    Hashtag explore URL'leri dinamik olduğundan kontrol yapılmaz (her zaman çek)
     *
     * @param target değerlendirilecek hedef
     * @return pencere içinde analizi varsa true (Apify + AI atlanır)
     */
    @Transactional(readOnly = true)
    public boolean isRecentlyAnalyzed(ScrapeTarget target) {
        // SECTOR hedefleri her zaman taze çekilir (hashtag içeriği değişkendir)
        if (target.type() == ScrapeTarget.TargetType.SECTOR) {
            return false;
        }

        Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusDays(DEFAULT_ANALYSIS_PERIOD_DAYS));

        List<UUID> rows;
        switch (target.type()) {
            case MONITORED -> {
                // Rakip hesap: monitored_account_id eşleşmesi
                String sql = """
                        SELECT sp.social_post_id
                        FROM social_post sp, post_analysis pa
                        WHERE sp.social_post_id = pa.social_post_id
                          AND sp.monitored_account_id = ?
                          AND pa.created_date >= ?
                        """;
                rows = jdbcTemplate.query(sql,
                        (rs, rowNum) -> rs.getObject("social_post_id", UUID.class),
                        target.monitoredAccountId(), cutoff);
            }
            case OWN -> {
                // Kendi hesabı: report_request.selected_user_social_account_id üzerinden.
                // KRİTİK: sp.source_type = 'OWN' filtresi ZORUNLU — aksi halde aynı istekte
                // çekilen SECTOR postları da eşleşir (hepsi aynı request_id'ye bağlı), ve OWN
                // scraping'i hiç başarılı olmamış olsa bile "yakın zamanda analiz edilmiş"
                // sanılıp bir sonraki denemede Apify'a HİÇ gidilmeden atlanır. Gerçek vakada
                // bulundu: bi_butik_originals'ın OWN scraping'i başarısız oldu ama 5 SECTOR
                // postu analiz edildi; hesap mylovebutik olarak değiştirilip tekrar denendiğinde
                // bu sorgu o eski SECTOR postlarını "OWN analiz edilmiş" sayıp mylovebutik'i
                // Apify'a hiç göndermedi.
                String sql = """
                        SELECT sp.social_post_id
                        FROM social_post sp, post_analysis pa, report_request rr
                        WHERE sp.social_post_id = pa.social_post_id
                          AND sp.request_id = rr.request_id
                          AND sp.source_type = 'OWN'
                          AND rr.selected_user_social_account_id = ?
                          AND pa.created_date >= ?
                        """;
                rows = jdbcTemplate.query(sql,
                        (rs, rowNum) -> rs.getObject("social_post_id", UUID.class),
                        target.selectedUserSocialAccountId(), cutoff);
            }
            default -> rows = List.of();
        }

        boolean recent = !rows.isEmpty();
        if (recent) {
            log.info("Tekrar-analiz koruması: hesap son {} günde analiz edilmiş, atlanıyor (tip={}, hesap={}).",
                    DEFAULT_ANALYSIS_PERIOD_DAYS, target.type(), target.accountName());
        }
        return recent;
    }

    /**
     * Tekrar-analiz koruması devreye girdiğinde (isRecentlyAnalyzed=true), mevcut post'ları
     * yeni requestId'ye bağlar. Bu olmadan analiz ve rapor sorguları (request_id bazlı)
     * hiçbir post görmez ve pipeline FAILED döner.
     *
     * @param requestId yeni rapor isteğinin id'si
     * @param target    cache hit olan hedef
     * @return güncellenen satır sayısı
     */
    @Transactional
    public int relinkExistingPosts(UUID requestId, ScrapeTarget target) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int updated;
        switch (target.type()) {
            case MONITORED -> {
                // Rakip hesap: monitored_account_id üzerinden bul, yeni isteğe bağla
                String sql = """
                        UPDATE social_post
                        SET request_id = ?, updated_date = ?
                        WHERE monitored_account_id = ?
                        """;
                updated = jdbcTemplate.update(sql, requestId, now, target.monitoredAccountId());
            }
            case OWN -> {
                // Kendi hesabı: aynı selected_user_social_account_id'ye ait eski request'lerdeki
                // OWN post'ları yeni isteğe bağla (source_type ile daralt — SECTOR post'larını koru)
                String sql = """
                        UPDATE social_post
                        SET request_id = ?, updated_date = ?
                        WHERE source_type = 'OWN'
                          AND request_id IN (
                              SELECT rr.request_id
                              FROM report_request rr
                              WHERE rr.selected_user_social_account_id = ?
                          )
                        """;
                updated = jdbcTemplate.update(sql, requestId, now, target.selectedUserSocialAccountId());
            }
            default -> updated = 0;
        }
        log.info("Tekrar-analiz koruması: mevcut post'lar yeni isteğe bağlandı: requestId={}, tip={}, hesap={}, güncellenen={}",
                requestId, target.type(), target.accountName(), updated);
        return updated;
    }

    /**
     * Bir hedefin Apify'dan çekilen gönderilerini social_post'a yazar (save-or-update).
     * Mevcut gönderi varsa etkileşim metrikleri + result_json güncellenir, request_id yenilenir.
     * Yeni gönderi ise JPA save ile insert edilir.
     *
     * @return eklenen (yeni) gönderi sayısı
     */
    @Transactional
    public int saveRecentPosts(UUID requestId, ScrapeTarget target, List<ApifyPost> posts) {
        if (posts == null || posts.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;

        for (ApifyPost post : posts) {
            // Mevcut gönderi var mı? (platform + platform_post_id unique key)
            String dupSql = """
                    SELECT social_post_id
                    FROM social_post
                    WHERE platform = ? AND platform_post_id = ?
                    """;
            List<UUID> existing = jdbcTemplate.query(dupSql,
                    (rs, rowNum) -> rs.getObject("social_post_id", UUID.class),
                    target.platform(), post.platformPostId());

            if (!existing.isEmpty()) {
                // Güncelle: etkileşim metrikleri + ham JSON + bağlı request_id yenilenir
                String updateSql = """
                        UPDATE social_post
                        SET request_id     = ?,
                            likes_count    = ?,
                            comments_count = ?,
                            views_count    = ?,
                            shares_count   = ?,
                            result_json    = ?,
                            updated_date   = ?
                        WHERE platform = ? AND platform_post_id = ?
                        """;
                jdbcTemplate.update(updateSql,
                        requestId,
                        post.likesCount(), post.commentsCount(),
                        post.viewsCount(), post.sharesCount(),
                        post.rawJson(),
                        Timestamp.valueOf(now),
                        target.platform(), post.platformPostId());
                log.debug("Gönderi güncellendi: platform={}, postId={}", target.platform(), post.platformPostId());
                continue;
            }

            // Yeni gönderi: JPA save ile insert
            SocialPost sp = new SocialPost();
            sp.setSocialPostId(UUID.randomUUID());
            sp.setRequestId(requestId);

            // Kaynak kimlik kolonları (hedef tipine göre)
            sp.setMonitoredAccountId(target.type() == ScrapeTarget.TargetType.MONITORED
                    ? target.monitoredAccountId() : null);

            // source_type kaynak ayrımını taşır (TargetType ile birebir: OWN | MONITORED | SECTOR)
            sp.setSourceType(target.type().name());

            // SECTOR: sector_account_name, hesap adı Apify yanıtından (ownerUsername); diğerlerinde null
            if (target.type() == ScrapeTarget.TargetType.SECTOR) {
                sp.setSectorAccountName(post.ownerUsername());
            } else {
                sp.setSectorAccountName(null);
            }

            // Gönderi alanları
            sp.setPlatform(target.platform());
            sp.setPlatformPostId(post.platformPostId());
            sp.setPostUrl(post.postUrl());
            sp.setCaption(post.caption());
            sp.setHashtags(post.hashtags());
            sp.setMediaUrl(post.mediaUrl());
            sp.setMediaType(post.mediaType());
            sp.setLikesCount(post.likesCount());
            sp.setCommentsCount(post.commentsCount());
            sp.setViewsCount(post.viewsCount());
            sp.setSharesCount(post.sharesCount());
            sp.setPostDate(post.postDate());
            // Ham Apify JSON'u (result_json — OpenAI analiz promptuna gidecek)
            sp.setResultJson(post.rawJson());
            sp.setCreatedDate(now);
            sp.setUpdatedDate(now);

            // JPA save ile insert
            socialPostRepository.save(sp);
            inserted++;
        }

        log.info("social_post işlendi: requestId={}, tip={}, hesap={}, gelen={}, yeni={}",
                requestId, target.type(), target.accountName(), posts.size(), inserted);
        return inserted;
    }
}
