-- ============================================================
-- V11 — Ücretsiz ilk kullanım (1 rapor + 1 post/story) (Gökhan onayı, 2026-07-12)
-- ============================================================
-- Karar: her kullanıcı (mevcut VE yeni) 1 kez ücretsiz rapor + buna SIRALI BAĞIMLI olarak
-- 1 kez ücretsiz post/story üretebilir. Kontrol/kayıt tamamen bu yeni tablodan yapılır,
-- kredi sistemine (user_payment.credit_balance) HİÇ dokunulmaz.
--
-- Kurallar (kod tarafında uygulanır, bu migration sadece şemayı kurar):
--   - free_report_used=1 olduktan sonra bir daha ücretsiz rapor üretilemez.
--   - free_content_used SADECE free_report_request_id'den üretilen raporda, SADECE POST/STORY
--     için kullanılabilir (Carousel/Reel asla ücretsiz değildir).
--   - "Herkese 1 hak tanı" kararı gereği mevcut TÜM kullanıcılar için de satır seed edilir
--     (aşağıdaki INSERT) — bu, zaten çok sayıda rapor/içerik üretmiş test hesapları dahil
--     HERKESİN bir sonraki rapor/içerik üretiminde 1 kez ücretsiz sayılacağı anlamına gelir.
-- ============================================================

CREATE TABLE user_free_usage (
    user_id                 UUID PRIMARY KEY,
    free_report_used        INTEGER NOT NULL DEFAULT 0,
    free_report_request_id  UUID,
    free_report_used_date   TIMESTAMP,
    free_content_used       INTEGER NOT NULL DEFAULT 0,
    free_content_id         UUID,
    free_content_used_date  TIMESTAMP,
    created_date            TIMESTAMP NOT NULL,
    updated_date             TIMESTAMP NOT NULL
);

-- Mevcut tüm kullanıcılar için satır seed et (herkese 1 hak tanı kararı)
INSERT INTO user_free_usage (user_id, free_report_used, free_content_used, created_date, updated_date)
SELECT user_id, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM user_info;

-- report_request / content_request: bu istek ücretsiz ilk kullanım hakkıyla mı oluşturuldu?
-- Kredi düşümü (COMPLETED sonrası) bu bayrak true ise atlanır — reconciliation'ın onu
-- "başarısız düşüm" sanıp tekrar denemesini engellemek için credit_debited yine 1 yazılır.
ALTER TABLE report_request ADD COLUMN is_free_usage INTEGER NOT NULL DEFAULT 0;
ALTER TABLE content_request ADD COLUMN is_free_usage INTEGER NOT NULL DEFAULT 0;
