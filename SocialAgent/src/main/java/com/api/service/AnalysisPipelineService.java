package com.api.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.ai.AiAnalysisService;
import com.api.entity.SocialPost;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI analiz pipeline'ı (CLAUDE.md Bölüm 11, D3).
 * Service interface yok (CLAUDE.md Madde 1); concrete @Service.
 *
 * Akış:
 *   1) Rapor isteğine ait, HENÜZ analiz edilmemiş social_post'ları çek (JdbcTemplate native).
 *   2) Her gönderiyi medya türüne göre uygun modele yönlendir (AiAnalysisService).
 *   3) Dönen JSON'ı post_analysis'e yaz (PostAnalysisService — dedup'lı).
 *
 * "Analiz edilmemiş" filtresi NOT EXISTS ile yapılır (anti-join). Böylece pipeline tekrar
 * çalışırsa aynı postlar yeniden analiz edilmez (idempotent).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisPipelineService {

    // Analiz edilmemiş postları çekmek için JdbcTemplate native
    private final JdbcTemplate jdbcTemplate;

    // Medya türüne göre AI yönlendirme
    private final AiAnalysisService aiAnalysisService;

    // post_analysis yazma + dedup
    private final PostAnalysisService postAnalysisService;

    /**
     * Bir rapor isteğinin analiz edilmemiş tüm gönderilerini analiz eder.
     *
     * @param requestId işlenecek rapor isteği
     * @return yazılan (yeni) analiz sayısı
     */
    @Transactional
    public int analyzeRequest(UUID requestId) {
        // 1) Henüz analizi olmayan gönderileri çek
        List<SocialPost> posts = loadUnanalyzedPosts(requestId);
        if (posts.isEmpty()) {
            log.info("Analiz edilecek yeni gönderi yok: requestId={}", requestId);
            return 0;
        }

        int analyzed = 0;
        // 2) Her gönderi için: OpenAI(result_json) + Gemini Vision(media_url) → birleşik analiz → yaz
        for (SocialPost post : posts) {
            String analysisJson = aiAnalysisService.analyzeFull(post);
            // Sonucu post_analysis'e yaz (dedup'lı; null ise yazmaz)
            if (postAnalysisService.saveAnalysis(post.getSocialPostId(), analysisJson)) {
                analyzed++;
            }
        }

        log.info("AI analiz tamamlandı: requestId={}, aday={}, yazılan={}", requestId, posts.size(), analyzed);
        return analyzed;
    }

    /**
     * Rapor isteğine ait, post_analysis'te kaydı OLMAYAN gönderileri çeker (anti-join, NOT EXISTS).
     * Yalnızca AI yönlendirmesi + prompt için gereken kolonlar okunur.
     */
    private List<SocialPost> loadUnanalyzedPosts(UUID requestId) {
        // NOT EXISTS ile "analizi olmayan" postlar (idempotent yeniden çalışma)
        String sql = """
                SELECT sp.social_post_id, sp.platform, sp.media_type, sp.media_url,
                       sp.post_url, sp.caption, sp.hashtags,
                       sp.likes_count, sp.comments_count, sp.views_count,
                       sp.result_json
                FROM social_post sp
                WHERE sp.request_id = ?
                  AND NOT EXISTS (
                        SELECT 1 FROM post_analysis pa
                        WHERE pa.social_post_id = sp.social_post_id
                  )
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            SocialPost sp = new SocialPost();
            sp.setSocialPostId(rs.getObject("social_post_id", UUID.class));
            sp.setPlatform(rs.getString("platform"));
            sp.setMediaType(rs.getString("media_type"));
            sp.setMediaUrl(rs.getString("media_url"));
            sp.setPostUrl(rs.getString("post_url"));
            sp.setCaption(rs.getString("caption"));
            sp.setHashtags(rs.getString("hashtags"));
            sp.setLikesCount(rs.getObject("likes_count", Long.class));
            sp.setCommentsCount(rs.getObject("comments_count", Long.class));
            sp.setViewsCount(rs.getObject("views_count", Long.class));
            sp.setResultJson(rs.getString("result_json"));
            return sp;
        }, requestId);
    }
}
