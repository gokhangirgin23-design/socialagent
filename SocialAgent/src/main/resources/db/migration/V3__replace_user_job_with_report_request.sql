-- ============================================================
-- SocialAgent - V3: user_job kaldırıldı, report_request eklendi
-- Scheduler kaldırıldı; iş istekleri oluşturulunca direkt kuyruğa basılır.
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumludur.
-- ============================================================

-- social_post.user_job_id -> request_id yeniden adlandır
ALTER TABLE social_post RENAME COLUMN user_job_id TO request_id;

-- report.user_job_id -> request_id yeniden adlandır
ALTER TABLE report RENAME COLUMN user_job_id TO request_id;

-- social_post eski index'lerini kaldır, yenilerini oluştur
DROP INDEX IF EXISTS idx_social_post_job_id;
DROP INDEX IF EXISTS idx_social_post_job_source;
CREATE INDEX idx_social_post_request_id ON social_post (request_id);
CREATE INDEX idx_social_post_request_source ON social_post (request_id, source_type);

-- report eski index'ini kaldır, yenisini oluştur
DROP INDEX IF EXISTS idx_report_job_id;
CREATE INDEX idx_report_request_id ON report (request_id);

-- user_job tablosuna ait index'leri kaldır
DROP INDEX IF EXISTS idx_user_job_user_id;
DROP INDEX IF EXISTS idx_user_job_active_completed;
DROP INDEX IF EXISTS idx_user_job_queue_scan;
DROP INDEX IF EXISTS idx_user_job_next_run;
DROP INDEX IF EXISTS idx_user_job_last_report;

-- user_job tablosunu kaldır
DROP TABLE IF EXISTS user_job;

-- Rapor isteği tablosu
-- report_type: OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE
-- queue_pushed: 0 = henüz kuyruğa basılmadı, 1 = kuyruğa basıldı
-- queue_error: kuyruğa basma başarısız olursa hata mesajı buraya yazılır
-- selected_user_social_account_id: OWN_ONLY / BOTH modunda kullanıcının seçilen kendi hesabı
CREATE TABLE report_request (
    request_id                       UUID PRIMARY KEY,
    user_id                          UUID NOT NULL,
    report_type                      VARCHAR(30),
    selected_user_social_account_id  UUID,
    queue_pushed                     INTEGER NOT NULL DEFAULT 0,
    queue_push_date                  TIMESTAMP,
    queue_error                      TEXT,
    active                           INTEGER NOT NULL DEFAULT 1,
    created_date                     TIMESTAMP,
    updated_date                     TIMESTAMP
);
CREATE INDEX idx_report_request_user_id ON report_request (user_id);
