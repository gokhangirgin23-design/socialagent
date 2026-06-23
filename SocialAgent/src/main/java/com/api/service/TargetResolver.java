package com.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.api.apify.ApifyClient;
import com.api.apify.ApifyProfile;
import com.api.config.AppProperties;
import com.api.entity.AnalysisMode;
import com.api.entity.Platform;
import com.api.entity.ReportRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bir rapor isteği için report_type'a göre çekilecek hedef listesini belirler.
 * Service interface yok (CLAUDE.md Madde 1); concrete @Component.
 *
 * | Tür              | Hedefler                                                              |
 * | NONE             | Sektör keyword → Apify top N profil araması → profil URL'leri (D1)   |
 * | OWN_ONLY         | Kendi hesabı + sektör profil URL'leri (D1)                           |
 * | COMPETITOR_ONLY  | Yalnızca rakip (monitored) hesap profil URL'leri                     |
 * | BOTH             | Kendi hesabı + rakip hesap profil URL'leri                           |
 *
 * Lookup'lar JdbcTemplate native; ilişkili tablolar eski stil "=" join (CLAUDE.md Madde 6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetResolver {

    // Native sorgular için JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // D1: sektör keyword araması (profil-bazlı; hashtag explore yerine)
    private final ApifyClient apifyClient;

    // top-profiles-limit config ayarı için
    private final AppProperties appProperties;

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
                // D1: Apify keyword araması → sektörün top N profili → SECTOR hedefler
                targets.addAll(resolveSectorByProfiles(request.getUserId()));
            }
            case OWN_ONLY -> {
                // Önce kendi hesabı, sonra D1 sektör profilleri
                addIfPresent(targets, resolveOwn(request.getSelectedUserSocialAccountId()));
                targets.addAll(resolveSectorByProfiles(request.getUserId()));
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
     * Sektör araştırması (D1 — CLAUDE.md Bölüm 10):
     * Apify keyword aramasıyla sektörün top N Instagram profilini bulur;
     * her profil için profil URL'si SECTOR ScrapeTarget'a dönüştürülür.
     * Hashtag explore URL yaklaşımı yerine D1'in öngördüğü profil araması kullanılır.
     */
    private List<ScrapeTarget> resolveSectorByProfiles(UUID userId) {
        // 1) Kullanıcının alt-sektör (önce) veya sektör adını keyword olarak belirle
        String keyword = loadUserSectorKeyword(userId);
        if (keyword == null || keyword.isBlank()) {
            log.warn("Sektör keyword belirlenemedi, SECTOR hedef oluşturulamadı: userId={}", userId);
            return List.of();
        }
        // 2) Apify profil araması: en iyi N hesap (follower/engagement'a göre sıralı)
        int topN = appProperties.getApify().getTopProfilesLimit();
        List<ApifyProfile> profiles = apifyClient.searchTopProfiles(keyword, topN);
        if (profiles.isEmpty()) {
            log.warn("Apify sektör profil araması boş döndü: keyword={}", keyword);
            return List.of();
        }
        // 3) Her profil için Instagram profil URL'si ile SECTOR hedef oluştur
        List<ScrapeTarget> targets = new ArrayList<>();
        for (ApifyProfile profile : profiles) {
            String url = (profile.accountName() != null && !profile.accountName().isBlank())
                    ? "https://www.instagram.com/" + profile.accountName() + "/"
                    : profile.profileUrl();
            if (url == null || url.isBlank()) {
                continue;
            }
            targets.add(ScrapeTarget.sector(Platform.INSTAGRAM.name(), url));
        }
        log.info("Sektör profil hedefleri çözüldü: keyword={}, hedefSayısı={}", keyword, targets.size());
        return targets;
    }

    /**
     * Kullanıcının alt-sektör (önce) veya sektör adını döndürür.
     * Apify keyword araması için kullanılır (D1).
     */
    private String loadUserSectorKeyword(UUID userId) {
        // Sektör ve alt-sektör id'lerini çek
        String refSql = """
                SELECT sector_id, subsector_id
                FROM user_info
                WHERE user_id = ? AND active = 1
                """;
        List<UUID[]> refs = jdbcTemplate.query(refSql, (rs, rowNum) -> new UUID[]{
                rs.getObject("sector_id", UUID.class),
                rs.getObject("subsector_id", UUID.class)}, userId);
        if (refs.isEmpty()) {
            return null;
        }
        UUID sectorId = refs.get(0)[0];
        UUID subsectorId = refs.get(0)[1];
        if (sectorId == null) {
            return null;
        }
        // Alt-sektör adı önce (daha spesifik)
        if (subsectorId != null) {
            List<String> names = jdbcTemplate.query(
                    "SELECT name FROM subsector WHERE subsector_id = ? AND active = 1",
                    (rs, rowNum) -> rs.getString("name"), subsectorId);
            if (!names.isEmpty() && names.get(0) != null) {
                return names.get(0);
            }
        }
        // Sektör adı
        List<String> names = jdbcTemplate.query(
                "SELECT name FROM sector WHERE sector_id = ? AND active = 1",
                (rs, rowNum) -> rs.getString("name"), sectorId);
        return names.isEmpty() ? null : names.get(0);
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
