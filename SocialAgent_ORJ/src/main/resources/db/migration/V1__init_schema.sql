-- ============================================================
-- SocialAgent - V1 ilk şema (CLAUDE.md Bölüm 5)
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumlu yazılmıştır.
-- Ortak alanlar: active (0/1), created_date, updated_date
-- Tüm PK'ler UUID. İlişkiler native sorgu ile çekileceği için
-- FK constraint eklenmedi; performans için index'ler eklendi.
-- ============================================================

-- Sektör tablosu
CREATE TABLE sector (
    sector_id      UUID PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    active         INTEGER      NOT NULL DEFAULT 1,
    created_date   TIMESTAMP,
    updated_date   TIMESTAMP
);

-- Alt sektör tablosu
CREATE TABLE subsector (
    subsector_id   UUID PRIMARY KEY,
    sector_id      UUID         NOT NULL,
    name           VARCHAR(255) NOT NULL,
    active         INTEGER      NOT NULL DEFAULT 1,
    created_date   TIMESTAMP,
    updated_date   TIMESTAMP,
    CONSTRAINT uq_subsector_sector_name UNIQUE (sector_id, name)
);
CREATE INDEX idx_subsector_sector_id ON subsector (sector_id);

-- Kullanıcı bilgisi
CREATE TABLE user_info (
    user_id            UUID PRIMARY KEY,
    google_id          VARCHAR(255),
    email              VARCHAR(320),
    full_name          VARCHAR(255),
    profile_photo_url  VARCHAR(1000),
    sector_id          UUID,
    subsector_id       UUID,
    active             INTEGER NOT NULL DEFAULT 1,
    created_date       TIMESTAMP,
    updated_date       TIMESTAMP,
    CONSTRAINT uq_user_info_google_id UNIQUE (google_id),
    CONSTRAINT uq_user_info_email     UNIQUE (email)
);

-- Refresh token
CREATE TABLE refresh_token (
    refresh_token_id   UUID PRIMARY KEY,
    user_id            UUID NOT NULL,
    token              VARCHAR(1000) NOT NULL,
    expire_date        TIMESTAMP,
    active             INTEGER NOT NULL DEFAULT 1,
    created_date       TIMESTAMP,
    updated_date       TIMESTAMP
);
CREATE INDEX idx_refresh_token_user_id ON refresh_token (user_id);

-- Kullanıcının kendi sosyal hesabı (tek hesap - D2)
CREATE TABLE user_social_account (
    user_social_account_id  UUID PRIMARY KEY,
    user_id                 UUID NOT NULL,
    platform                VARCHAR(50)  NOT NULL,
    account_name            VARCHAR(255) NOT NULL,
    profile_url             VARCHAR(1000),
    active                  INTEGER NOT NULL DEFAULT 1,
    created_date            TIMESTAMP,
    updated_date            TIMESTAMP,
    CONSTRAINT uq_usa_user_platform_account UNIQUE (user_id, platform, account_name)
);
CREATE INDEX idx_usa_user_id ON user_social_account (user_id);

-- Global izlenen hesap havuzu
CREATE TABLE monitored_account (
    monitored_account_id  UUID PRIMARY KEY,
    platform              VARCHAR(50)  NOT NULL,
    account_name          VARCHAR(255) NOT NULL,
    active                INTEGER NOT NULL DEFAULT 1,
    created_date          TIMESTAMP,
    updated_date          TIMESTAMP,
    CONSTRAINT uq_ma_platform_account UNIQUE (platform, account_name)
);

-- Kullanıcı <-> izlenen hesap eşleşmesi
CREATE TABLE user_monitored_account (
    user_monitored_account_id  UUID PRIMARY KEY,
    user_id                    UUID NOT NULL,
    monitored_account_id       UUID NOT NULL,
    active                     INTEGER NOT NULL DEFAULT 1,
    created_date               TIMESTAMP,
    updated_date               TIMESTAMP,
    CONSTRAINT uq_uma_user_monitored UNIQUE (user_id, monitored_account_id)
);
CREATE INDEX idx_uma_user_id ON user_monitored_account (user_id);
CREATE INDEX idx_uma_monitored_id ON user_monitored_account (monitored_account_id);

-- İş (job) kaydı
CREATE TABLE user_job (
    user_job_id                      UUID PRIMARY KEY,
    user_id                          UUID NOT NULL,
    selected_user_social_account_id  UUID,
    analysis_mode                    VARCHAR(30),   -- OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE
    job_period                       VARCHAR(20),   -- DAILY | WEEKLY | MONTHLY | ON_DEMAND
    analysis_period_days             INTEGER DEFAULT 7,
    repeat_count                     INTEGER,
    current_count                    INTEGER NOT NULL DEFAULT 0,
    completed                        INTEGER NOT NULL DEFAULT 0,
    active                           INTEGER NOT NULL DEFAULT 1,
    created_date                     TIMESTAMP,
    updated_date                     TIMESTAMP
);
CREATE INDEX idx_user_job_user_id ON user_job (user_id);
-- Scheduler bu index'i kullanır (active=1 AND completed=0)
CREATE INDEX idx_user_job_active_completed ON user_job (active, completed);

-- Çekilen sosyal medya gönderileri
CREATE TABLE social_post (
    social_post_id        UUID PRIMARY KEY,
    user_job_id           UUID NOT NULL,
    monitored_account_id  UUID,            -- nullable
    platform_sector       VARCHAR(50),     -- nullable (sektör top-5 platformu)
    account_name_sector   VARCHAR(255),    -- nullable (sektör top-5 hesabı)
    platform              VARCHAR(50)  NOT NULL,
    platform_post_id      VARCHAR(255) NOT NULL,
    post_url              VARCHAR(1000),
    caption               TEXT,
    hashtags              VARCHAR(2000),
    media_url             VARCHAR(1000),
    media_type            VARCHAR(30),     -- IMAGE | VIDEO | CAROUSEL | TEXT
    likes_count           BIGINT,
    comments_count        BIGINT,
    views_count           BIGINT,
    shares_count          BIGINT,
    post_date             TIMESTAMP,
    created_date          TIMESTAMP,
    updated_date          TIMESTAMP,
    CONSTRAINT uq_social_post_platform_postid UNIQUE (platform, platform_post_id)
);
CREATE INDEX idx_social_post_job_id ON social_post (user_job_id);
CREATE INDEX idx_social_post_monitored_id ON social_post (monitored_account_id);

-- Post analiz sonucu (AI JSON çıktısı)
CREATE TABLE post_analysis (
    post_analysis_id  UUID PRIMARY KEY,
    social_post_id    UUID NOT NULL,
    analysis_json     TEXT,            -- prod'da JSONB'ye taşınabilir
    created_date      TIMESTAMP,
    updated_date      TIMESTAMP
);
CREATE INDEX idx_post_analysis_post_id ON post_analysis (social_post_id);

-- Rapor (Markdown)
CREATE TABLE report (
    report_id        UUID PRIMARY KEY,
    user_job_id      UUID NOT NULL,
    status           VARCHAR(20),     -- PENDING | GENERATING | COMPLETED | FAILED
    report_content   TEXT,            -- Markdown
    created_date     TIMESTAMP,
    updated_date     TIMESTAMP
);
CREATE INDEX idx_report_job_id ON report (user_job_id);

-- Bildirim
CREATE TABLE notification (
    notification_id  UUID PRIMARY KEY,
    user_id          UUID NOT NULL,
    title            VARCHAR(255),
    message          TEXT,
    reference_type   VARCHAR(30),     -- REPORT | JOB
    reference_id     UUID,
    is_read          INTEGER NOT NULL DEFAULT 0,
    created_date     TIMESTAMP,
    updated_date     TIMESTAMP
);
CREATE INDEX idx_notification_user_id ON notification (user_id);
