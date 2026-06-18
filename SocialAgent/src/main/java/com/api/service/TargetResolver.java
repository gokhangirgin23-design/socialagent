package com.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.api.entity.AnalysisMode;
import com.api.entity.Platform;
import com.api.entity.ReportRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bir rapor isteği için report_type'a göre çekilecek hedef listesini belirler.
 * Service interface yok (CLAUDE.md Madde 1); concrete @Component.
 *
 * | Tür              | Hedefler                                                        |
 * | NONE             | Sektör hashtag explore URL'leri (OpenAI → top 5 hashtag)        |
 * | OWN_ONLY         | Kendi hesabı + sektör hashtag explore URL'leri                  |
 * | COMPETITOR_ONLY  | Yalnızca rakip (monitored) hesap profil URL'leri                |
 * | BOTH             | Kendi hesabı + rakip hesap profil URL'leri                      |
 *
 * Lookup'lar JdbcTemplate native; ilişkili tablolar eski stil "=" join (CLAUDE.md Madde 6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetResolver {

    // Native sorgular için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // OpenAI ile sektör hashtag'lerini ve explore URL'lerini üreten servis
    private final HashtagService hashtagService;

    /**
     * Rapor isteğinin tipine göre hedef listesini üretir.
     *
     * @param request işlenecek rapor isteği
     * @return çekilecek hedefler (boş olabilir)
     */
    public List<ScrapeTarget> resolve(ReportRequest request) {
        AnalysisMode mode = parseMode(request.getReportType());
        List<ScrapeTarget> targets = new ArrayList<>();

        switch (mode) {
            case NONE -> {
                // Yalnızca sektör hashtag explore URL'leri
                targets.addAll(resolveSectorHashtags(request.getUserId()));
            }
            case OWN_ONLY -> {
                // Önce kendi hesabı, sonra sektör hashtag explore URL'leri
                addIfPresent(targets, resolveOwn(request.getSelectedUserSocialAccountId()));
                targets.addAll(resolveSectorHashtags(request.getUserId()));
            }
            case COMPETITOR_ONLY -> {
                // Yalnızca rakip hesap profil URL'leri
                targets.addAll(resolveMonitored(request.getUserId()));
            }
            case BOTH -> {
                // Kendi hesabı + rakip hesap profil URL'leri (sektör araştırması yok)
                addIfPresent(targets, resolveOwn(request.getSelectedUserSocialAccountId()));
                targets.addAll(resolveMonitored(request.getUserId()));
            }
        }

        log.info("Hedefler çözüldü: requestId={}, mod={}, hedefSayısı={}",
                request.getRequestId(), mode, targets.size());
        return targets;
    }

    // ============================================================
    // Mod bileşenleri
    // ============================================================

    /**
     * Sektör araştırması: OpenAI → top 5 hashtag → Instagram explore tag URL'leri → SECTOR hedefler.
     */
    private List<ScrapeTarget> resolveSectorHashtags(UUID userId) {
        List<String> exploreUrls = hashtagService.resolveExploreUrls(userId);
        if (exploreUrls.isEmpty()) {
            log.warn("Sektör hashtag URL'leri oluşturulamadı: userId={}", userId);
            return List.of();
        }
        List<ScrapeTarget> targets = new ArrayList<>();
        for (String url : exploreUrls) {
            targets.add(ScrapeTarget.sector(Platform.INSTAGRAM.name(), url));
        }
        return targets;
    }

    /**
     * Kendi (tek) hesabı OWN hedefine çevirir; hesap yoksa null.
     */
    private ScrapeTarget resolveOwn(UUID selectedUserSocialAccountId) {
        if (selectedUserSocialAccountId == null) {
            return null;
        }
        String sql = """
                SELECT platform, account_name
                FROM user_social_account
                WHERE user_social_account_id = ? AND active = 1
                """;
        List<ScrapeTarget> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> ScrapeTarget.own(
                        rs.getString("platform"),
                        rs.getString("account_name"),
                        selectedUserSocialAccountId),
                selectedUserSocialAccountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Kullanıcının izlediği rakip hesapları MONITORED hedeflerine çevirir.
     * user_monitored_account ve monitored_account eski stil "=" join (CLAUDE.md Madde 6).
     */
    private List<ScrapeTarget> resolveMonitored(UUID userId) {
        String sql = """
                SELECT ma.monitored_account_id, ma.platform, ma.account_name
                FROM user_monitored_account uma, monitored_account ma
                WHERE uma.user_id = ?
                  AND uma.monitored_account_id = ma.monitored_account_id
                  AND uma.active = 1
                  AND ma.active = 1
                ORDER BY ma.account_name
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> ScrapeTarget.monitored(
                rs.getString("platform"),
                rs.getString("account_name"),
                rs.getObject("monitored_account_id", UUID.class)), userId);
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    private void addIfPresent(List<ScrapeTarget> targets, ScrapeTarget target) {
        if (target != null) {
            targets.add(target);
        }
    }

    private AnalysisMode parseMode(String value) {
        try {
            return AnalysisMode.valueOf(value);
        } catch (Exception e) {
            log.warn("Geçersiz reportType='{}', NONE varsayıldı.", value);
            return AnalysisMode.NONE;
        }
    }
}
