-- ============================================================
-- SocialAgent - V1 İlk şema (CLAUDE.md Bölüm 5)
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumludur.
-- Ortak alanlar: active (0/1), created_date, updated_date. Tüm PK'ler UUID.
-- İlişkiler native sorgu ile çekildiğinden FK constraint yok; index'ler var.
-- ============================================================

-- ============================================================
-- ŞEMA
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
-- token üzerinde lookup index'i: findActiveRefreshToken WHERE token = ?
CREATE INDEX idx_refresh_token_token ON refresh_token (token);

-- Kullanıcının kendi sosyal hesabı (tek hesap - D2)
CREATE TABLE user_social_account (
    user_social_account_id  UUID PRIMARY KEY,
    user_id                 UUID NOT NULL,
    platform                VARCHAR(50)  NOT NULL,
    account_name            VARCHAR(255) NOT NULL,
    profile_url             VARCHAR(1000),  -- platforma göre otomatik üretilir (INSTAGRAM şimdilik)
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
    profile_url           VARCHAR(1000),  -- platforma göre otomatik üretilir (INSTAGRAM şimdilik)
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

-- Rapor isteği (scheduler yok; istek oluşunca direkt kuyruğa basılır)
-- report_type: OWN_ONLY | COMPETITOR_ONLY | NONE
-- queue_pushed: 0 = kuyruğa basılmadı, 1 = basıldı
-- selected_user_social_account_id: OWN_ONLY modunda kullanıcının seçilen kendi hesabı
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

-- Çekilen sosyal medya gönderileri
-- request_id: bağlı rapor isteği
-- source_type: OWN | MONITORED | SECTOR  (kaynak ayrımı bu kolondan)
-- result_json: Apify ham JSON (OpenAI analiz promptuna ham veri)
CREATE TABLE social_post (
    social_post_id        UUID PRIMARY KEY,
    request_id            UUID NOT NULL,
    monitored_account_id  UUID,            -- nullable (yalnız MONITORED)
    source_type           VARCHAR(20),     -- OWN | MONITORED | SECTOR
    sector_account_name   VARCHAR(255),    -- nullable (yalnız SECTOR hesabı adı)
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
    result_json           TEXT,            -- Apify ham JSON
    created_date          TIMESTAMP,
    updated_date          TIMESTAMP,
    CONSTRAINT uq_social_post_platform_postid UNIQUE (platform, platform_post_id)
);
CREATE INDEX idx_social_post_request_id ON social_post (request_id);
CREATE INDEX idx_social_post_monitored_id ON social_post (monitored_account_id);
-- Rapor sorgusu kaynak bazlı gruplama yapar (request_id + source_type)
CREATE INDEX idx_social_post_request_source ON social_post (request_id, source_type);

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
-- request_id: bağlı rapor isteği
CREATE TABLE report (
    report_id        UUID PRIMARY KEY,
    request_id       UUID NOT NULL,
    status           VARCHAR(20),     -- PENDING | GENERATING | COMPLETED | FAILED
    report_content   TEXT,            -- Markdown
    created_date     TIMESTAMP,
    updated_date     TIMESTAMP
);
CREATE INDEX idx_report_request_id ON report (request_id);

-- Bildirim
-- reference_type: yalnız REPORT (reference_id = report_id)
-- channel: her rapor için MAIL + PUSH_NOTIFICATION (2 satır)
CREATE TABLE notification (
    notification_id  UUID PRIMARY KEY,
    user_id          UUID NOT NULL,
    title            VARCHAR(255),
    message          TEXT,
    reference_type   VARCHAR(30),     -- REPORT
    reference_id     UUID,
    channel          VARCHAR(30),     -- MAIL | PUSH_NOTIFICATION
    success          INTEGER,         -- 0/1 gönderim sonucu
    error_detail     TEXT,            -- success=0 ise hata/sebep
    is_read          INTEGER NOT NULL DEFAULT 0,
    created_date     TIMESTAMP,
    updated_date     TIMESTAMP
);
CREATE INDEX idx_notification_user_id ON notification (user_id);
CREATE INDEX idx_notification_user_created ON notification (user_id, created_date);
CREATE INDEX idx_notification_user_read ON notification (user_id, is_read);

-- ============================================================
-- Bakiye / Cüzdan + Hareket Defteri (ödeme)
-- ============================================================

-- Kullanıcı cüzdanı (kullanıcı başına TEK satır)
CREATE TABLE user_payment (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    balance         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency        VARCHAR(3) NOT NULL DEFAULT 'TL',
    total_topup     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_spent     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    active          INTEGER NOT NULL DEFAULT 1,
    created_date    TIMESTAMP,
    updated_date    TIMESTAMP,
    CONSTRAINT uq_user_payment_user UNIQUE (user_id)
);

-- Para hareketi + PayTR işlem kaydı (N satır)
-- transaction_type: TOPUP | DEBIT | REFUND
-- payment_status  : INITIATED | PENDING | SUCCESS | FAILED | EXPIRED
-- merchant_oid    : PayTR sipariş no (idempotensi anahtarı, UNIQUE)
-- processed       : bakiye bu satır için işlendi mi (0/1)
-- pending_*       : ödeme tamamlanınca oluşturulacak rapor isteğinin niyeti (deficit akışı)
CREATE TABLE user_payment_log (
    id                          UUID PRIMARY KEY,
    user_id                     UUID NOT NULL,
    user_payment_id             UUID NOT NULL,
    report_request_id           UUID,
    transaction_type            VARCHAR(20) NOT NULL,
    amount                      NUMERIC(19, 2) NOT NULL,
    currency                    VARCHAR(3) NOT NULL DEFAULT 'TL',
    balance_before              NUMERIC(19, 2),
    balance_after               NUMERIC(19, 2),
    merchant_oid                VARCHAR(64),
    payment_provider            VARCHAR(20) NOT NULL DEFAULT 'PAYTR',
    payment_status              VARCHAR(20),
    paytr_total_amount          NUMERIC(19, 2),
    payment_type                VARCHAR(20),
    installment_count           INTEGER DEFAULT 0,
    test_mode                   INTEGER DEFAULT 0,
    failed_reason_code          VARCHAR(10),
    failed_reason_msg           VARCHAR(255),
    callback_count              INTEGER NOT NULL DEFAULT 0,
    processed                   INTEGER NOT NULL DEFAULT 0,
    request_exp_date            TIMESTAMP,
    callback_raw                TEXT,
    pending_report_type         VARCHAR(30),
    pending_selected_account_id UUID,
    active                      INTEGER NOT NULL DEFAULT 1,
    created_date                TIMESTAMP,
    updated_date                TIMESTAMP,
    CONSTRAINT uq_upl_merchant_oid UNIQUE (merchant_oid)
);
CREATE INDEX idx_upl_user_id        ON user_payment_log (user_id);
CREATE INDEX idx_upl_status         ON user_payment_log (payment_status);
CREATE INDEX idx_upl_report_request ON user_payment_log (report_request_id);

-- ============================================================
-- TOHUM VERİSİ — Sektörler + Alt sektörler (sabit UUID; her ortamda aynı)
-- ============================================================

-- Sektörler (6)
INSERT INTO sector (sector_id, name, active, created_date, updated_date) VALUES
 ('11111111-1111-1111-1111-111111111101', 'Teknoloji', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111102', 'Moda',      1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111103', 'Yeme-İçme', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111104', 'Sağlık',    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111105', 'Eğitim',    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111106', 'Spor',      1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Teknoloji alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222101', '11111111-1111-1111-1111-111111111101', 'Mobil Uygulama',     1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222102', '11111111-1111-1111-1111-111111111101', 'Yapay Zeka',         1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222103', '11111111-1111-1111-1111-111111111101', 'Siber Güvenlik',     1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222104', '11111111-1111-1111-1111-111111111101', 'Yazılım Geliştirme', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Moda alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222201', '11111111-1111-1111-1111-111111111102', 'Giyim',                1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222202', '11111111-1111-1111-1111-111111111102', 'Aksesuar',             1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222203', '11111111-1111-1111-1111-111111111102', 'Güzellik ve Kozmetik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222204', '11111111-1111-1111-1111-111111111102', 'Lüks Moda',            1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Yeme-İçme alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222301', '11111111-1111-1111-1111-111111111103', 'Restoran',         1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222302', '11111111-1111-1111-1111-111111111103', 'Kafe ve Kahve',    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222303', '11111111-1111-1111-1111-111111111103', 'Fast Food',        1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222304', '11111111-1111-1111-1111-111111111103', 'Fırın ve Pastane', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Sağlık alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222401', '11111111-1111-1111-1111-111111111104', 'Klinik ve Hastane',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222402', '11111111-1111-1111-1111-111111111104', 'Fitness ve Wellness', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222403', '11111111-1111-1111-1111-111111111104', 'Beslenme ve Diyet',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Eğitim alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222501', '11111111-1111-1111-1111-111111111105', 'Online Eğitim',      1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222502', '11111111-1111-1111-1111-111111111105', 'Dil Öğrenimi',       1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222503', '11111111-1111-1111-1111-111111111105', 'Kurs ve Sertifika',  1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222504', '11111111-1111-1111-1111-111111111105', 'K-12 Eğitim',        1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Spor alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222601', '11111111-1111-1111-1111-111111111106', 'Spor Kulübü',       1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222602', '11111111-1111-1111-1111-111111111106', 'Spor Malzemeleri',  1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222603', '11111111-1111-1111-1111-111111111106', 'E-Spor',            1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
