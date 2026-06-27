-- İçerik üretimi tablosu (V7)
-- Kullanıcı bir rapor üzerinden görsel/caption üretim isteği açar;
-- worker üretimi asenkron yapar, durum bu tabloda izlenir.
CREATE TABLE content_request (
    content_request_id      UUID            PRIMARY KEY,
    user_id                 UUID            NOT NULL,
    report_id               UUID            NOT NULL,           -- report.report_id bağlantısı
    content_type            VARCHAR(20)     NOT NULL,           -- POST|STORY|CAROUSEL|REEL|ALL
    product_image_url       TEXT,                              -- kullanıcının yüklediği ürün görseli
    include_text_in_visual  BOOLEAN         NOT NULL DEFAULT FALSE,
    edit_instruction        TEXT,                              -- son düzenleme açıklaması (yeniden kuyruğa basılınca güncellenir)
    edit_count              INTEGER         NOT NULL DEFAULT 0, -- max edit-limit (config) hakkı
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING', -- PENDING|PROCESSING|COMPLETED|FAILED
    brand_dna_json          TEXT,                              -- OpenAI'dan üretilen Brand DNA (yeniden kullanılır)
    visual_urls             TEXT,                              -- JSON array; S3 URL'leri
    caption                 TEXT,
    hashtags                TEXT,
    cta                     TEXT,
    first_comment           TEXT,
    suggested_post_time     TEXT,
    process_started_date    TIMESTAMP,
    process_finished_date   TIMESTAMP,
    process_error           TEXT,
    attempt_count           INTEGER         NOT NULL DEFAULT 0,
    active                  SMALLINT        NOT NULL DEFAULT 1,
    created_date            TIMESTAMP,
    updated_date            TIMESTAMP
);

CREATE INDEX idx_content_request_user   ON content_request(user_id);
CREATE INDEX idx_content_request_report ON content_request(report_id);
CREATE INDEX idx_content_request_status ON content_request(status);
