-- Hesap bazlı Brand DNA cache: artık rapora değil, kullanıcının bağlı sosyal hesabına bağlanır.
-- İlk içerik üretiminde hesabın son 5 gönderisinden bir kez çıkarılır, sonra hep bu satırdan okunur.
CREATE TABLE user_account_dna (
    user_account_dna_id UUID          PRIMARY KEY,
    user_id              UUID          NOT NULL,
    social_account_id    UUID          NOT NULL,           -- user_social_account.user_social_account_id
    dna_json              TEXT          NOT NULL,           -- AI'dan dönen Brand DNA JSON
    source_post_count     INTEGER       NOT NULL DEFAULT 0, -- analiz edilen gönderi sayısı (<=5)
    active                 SMALLINT      NOT NULL DEFAULT 1,
    created_date            TIMESTAMP,
    updated_date             TIMESTAMP,
    CONSTRAINT uq_user_account_dna UNIQUE (user_id, social_account_id)
);
CREATE INDEX idx_user_account_dna_user ON user_account_dna(user_id);

-- İçerik üretimi artık rapordan bağımsız: report_id opsiyonel hale gelir, hesap bazlı üretim
-- için social_account_id eklenir.
ALTER TABLE content_request ALTER COLUMN report_id DROP NOT NULL;
ALTER TABLE content_request ADD COLUMN social_account_id UUID; -- user_social_account.user_social_account_id, opsiyonel
