-- ============================================================
-- SocialAgent - V1 İlk şema (CLAUDE.md Bölüm 5)
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumludur.
-- Ortak alanlar: active (0/1), created_date, updated_date. Tüm PK'ler UUID.
-- İlişkiler native sorgu ile çekildiğinden FK constraint yok; index'ler var.
-- NOT: Bu dosya eski V1-V8 migration'larının tek şema/tohum halinde
-- birleştirilmiş (squash) hâlidir; eski dosyalar artık yoktur.
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
-- NOT: DB-level UNIQUE(user_id, platform, account_name) kasıtlı olarak yok;
-- benzersizlik kontrolü service katmanında active=1 filtreli native sorgu ile yapılır
-- (pasif kayıtları da kapsayan DB constraint hesap değiştirmeyi engelliyordu).
CREATE TABLE user_social_account (
    user_social_account_id  UUID PRIMARY KEY,
    user_id                 UUID NOT NULL,
    platform                VARCHAR(50)  NOT NULL,
    account_name            VARCHAR(255) NOT NULL,
    profile_url             VARCHAR(1000),  -- platforma göre otomatik üretilir (INSTAGRAM şimdilik)
    active                  INTEGER NOT NULL DEFAULT 1,
    created_date            TIMESTAMP,
    updated_date            TIMESTAMP
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
-- NOT: DB-level UNIQUE(user_id, monitored_account_id) kasıtlı olarak yok;
-- kullanıcı rakip hesabı silip tekrar eklediğinde inactive kayıt kalabiliyor,
-- service katmanı bunu UPDATE ile yönetir (DB constraint kesin engel oluşturuyordu).
CREATE TABLE user_monitored_account (
    user_monitored_account_id  UUID PRIMARY KEY,
    user_id                    UUID NOT NULL,
    monitored_account_id       UUID NOT NULL,
    active                     INTEGER NOT NULL DEFAULT 1,
    created_date               TIMESTAMP,
    updated_date               TIMESTAMP
);
CREATE INDEX idx_uma_user_id ON user_monitored_account (user_id);
CREATE INDEX idx_uma_monitored_id ON user_monitored_account (monitored_account_id);

-- Rapor isteği (scheduler yok; istek oluşunca direkt kuyruğa basılır)
-- report_type: OWN_ONLY | COMPETITOR_ONLY | NONE
-- queue_pushed: 0 = kuyruğa basılmadı, 1 = basıldı
-- selected_user_social_account_id: OWN_ONLY modunda kullanıcının seçilen kendi hesabı
--
-- status: iş (job) yaşam döngüsü — PENDING -> PROCESSING -> COMPLETED | PARTIAL | FAILED
--   PENDING    : oluşturuldu/kuyrukta, worker henüz işlemedi
--   PROCESSING : worker işliyor (process_started_date set)
--   COMPLETED  : rapor üretildi VE tüm postlar analizli (analyzed == total)
--   PARTIAL    : rapor üretildi AMA eksik analiz var (yutulmuş dış-API hatası; analyzed < total)
--   FAILED     : işleme sırasında exception kaçtı / kullanılabilir rapor üretilemedi
-- attempt_count: requeue/sweep deneme sayacı (poison-message koruması; tavanı aşan istek tekrar seçilmez)
CREATE TABLE report_request (
    request_id                       UUID PRIMARY KEY,
    user_id                          UUID NOT NULL,
    report_type                      VARCHAR(30),
    selected_user_social_account_id  UUID,
    queue_pushed                     INTEGER NOT NULL DEFAULT 0,
    queue_push_date                  TIMESTAMP,
    queue_error                      TEXT,
    status                           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    process_started_date             TIMESTAMP,
    process_finished_date            TIMESTAMP,
    process_error                    TEXT,
    attempt_count                    INTEGER NOT NULL DEFAULT 0,
    active                           INTEGER NOT NULL DEFAULT 1,
    created_date                     TIMESTAMP,
    updated_date                     TIMESTAMP
);
CREATE INDEX idx_report_request_user_id ON report_request (user_id);
-- Sweep/requeue sorgusu status üzerinden filtrelediği için index
CREATE INDEX idx_report_request_status ON report_request (status);

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
-- insight_json: dashboard structured kart için (boş geçilebilir; frontend boş-durum gösterir)
CREATE TABLE report (
    report_id        UUID PRIMARY KEY,
    request_id       UUID NOT NULL,
    status           VARCHAR(20),     -- PENDING | GENERATING | COMPLETED | FAILED
    report_content   TEXT,            -- Markdown
    insight_json     TEXT,
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
-- report_id       : ödeme tamamlanıp rapor üretilince ScrapePipelineService tarafından doldurulur
CREATE TABLE user_payment_log (
    id                          UUID PRIMARY KEY,
    user_id                     UUID NOT NULL,
    user_payment_id             UUID NOT NULL,
    report_request_id           UUID,
    report_id                   UUID,
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
CREATE INDEX idx_upl_report_id      ON user_payment_log (report_id);

-- İçerik üretimi tablosu
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

-- ============================================================
-- TOHUM VERİSİ — Sektörler + Alt sektörler (sabit UUID; her ortamda aynı)
-- MVP için onaylanan tam liste: 30 sektör / 162 alt sektör.
-- ============================================================

-- Sektörler (30)
INSERT INTO sector (sector_id, name, active, created_date, updated_date) VALUES
 ('30000000-0000-0000-0000-000000000001', 'E-Ticaret', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000002', 'Sağlık', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000003', 'Güzellik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000004', 'Yeme & İçme', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000005', 'Otel & Turizm', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000006', 'Gayrimenkul', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000007', 'Otomotiv', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000008', 'Eğitim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000009', 'Spor', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000010', 'Finans', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000011', 'Hukuk', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000012', 'Teknoloji', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000013', 'Pazarlama', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000014', 'Fotoğraf & Video', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000015', 'Etkinlik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000016', 'Ev & Yaşam', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000017', 'İnşaat', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000018', 'Sanayi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000019', 'Tarım', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000020', 'Evcil Hayvan', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000021', 'Anne & Bebek', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000022', 'Moda', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000023', 'Kişisel Marka', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000024', 'Kamu & STK', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000025', 'Eğlence', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000026', 'Dijital İçerik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000027', 'Girişimcilik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000028', 'Yapay Zeka', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000029', 'Kripto & Web3', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000030', 'Lüks Yaşam', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- E-Ticaret alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'Moda', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001', 'Ayakkabı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000001', 'Takı & Aksesuar', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000004', '30000000-0000-0000-0000-000000000001', 'Kozmetik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000005', '30000000-0000-0000-0000-000000000001', 'Ev Dekorasyonu', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000006', '30000000-0000-0000-0000-000000000001', 'Mobilya', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000007', '30000000-0000-0000-0000-000000000001', 'Elektronik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000008', '30000000-0000-0000-0000-000000000001', 'Anne & Bebek', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000009', '30000000-0000-0000-0000-000000000001', 'Pet Ürünleri', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000010', '30000000-0000-0000-0000-000000000001', 'Hobi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Sağlık alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000011', '30000000-0000-0000-0000-000000000002', 'Diş Kliniği', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000012', '30000000-0000-0000-0000-000000000002', 'Estetik Merkezi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000013', '30000000-0000-0000-0000-000000000002', 'Psikolog', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000014', '30000000-0000-0000-0000-000000000002', 'Diyetisyen', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000015', '30000000-0000-0000-0000-000000000002', 'Fizyoterapi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000016', '30000000-0000-0000-0000-000000000002', 'Göz Kliniği', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000017', '30000000-0000-0000-0000-000000000002', 'Kadın Doğum', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000018', '30000000-0000-0000-0000-000000000002', 'Veteriner', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000019', '30000000-0000-0000-0000-000000000002', 'Tıp Merkezi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Güzellik alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000020', '30000000-0000-0000-0000-000000000003', 'Kuaför', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000021', '30000000-0000-0000-0000-000000000003', 'Berber', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000022', '30000000-0000-0000-0000-000000000003', 'Güzellik Salonu', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000023', '30000000-0000-0000-0000-000000000003', 'Nail Studio', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000024', '30000000-0000-0000-0000-000000000003', 'Lazer Epilasyon', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000025', '30000000-0000-0000-0000-000000000003', 'Cilt Bakımı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000026', '30000000-0000-0000-0000-000000000003', 'Kalıcı Makyaj', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Yeme & İçme alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000027', '30000000-0000-0000-0000-000000000004', 'Kafe', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000028', '30000000-0000-0000-0000-000000000004', 'Restoran', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000029', '30000000-0000-0000-0000-000000000004', 'Hamburger', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000030', '30000000-0000-0000-0000-000000000004', 'Pizza', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000031', '30000000-0000-0000-0000-000000000004', 'Tatlıcı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000032', '30000000-0000-0000-0000-000000000004', 'Pastane', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000033', '30000000-0000-0000-0000-000000000004', 'Kahve', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000034', '30000000-0000-0000-0000-000000000004', 'Fast Food', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000035', '30000000-0000-0000-0000-000000000004', 'Steakhouse', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000036', '30000000-0000-0000-0000-000000000004', 'Vegan', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Otel & Turizm alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000037', '30000000-0000-0000-0000-000000000005', 'Otel', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000038', '30000000-0000-0000-0000-000000000005', 'Butik Otel', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000039', '30000000-0000-0000-0000-000000000005', 'Tatil Köyü', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000040', '30000000-0000-0000-0000-000000000005', 'Seyahat Acentesi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000041', '30000000-0000-0000-0000-000000000005', 'Kamp Alanı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000042', '30000000-0000-0000-0000-000000000005', 'Glamping', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Gayrimenkul alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000043', '30000000-0000-0000-0000-000000000006', 'Emlak Ofisi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000044', '30000000-0000-0000-0000-000000000006', 'İnşaat Firması', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000045', '30000000-0000-0000-0000-000000000006', 'Konut Projesi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000046', '30000000-0000-0000-0000-000000000006', 'Ticari Gayrimenkul', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Otomotiv alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000047', '30000000-0000-0000-0000-000000000007', 'Galeri', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000048', '30000000-0000-0000-0000-000000000007', 'Oto Servis', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000049', '30000000-0000-0000-0000-000000000007', 'Oto Kuaför', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000050', '30000000-0000-0000-0000-000000000007', 'Lastik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000051', '30000000-0000-0000-0000-000000000007', 'Araç Kiralama', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000052', '30000000-0000-0000-0000-000000000007', 'Elektrikli Araç', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Eğitim alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000053', '30000000-0000-0000-0000-000000000008', 'Dil Kursu', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000054', '30000000-0000-0000-0000-000000000008', 'Yazılım Eğitimi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000055', '30000000-0000-0000-0000-000000000008', 'Özel Okul', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000056', '30000000-0000-0000-0000-000000000008', 'Kreş', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000057', '30000000-0000-0000-0000-000000000008', 'Üniversite Hazırlık', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000058', '30000000-0000-0000-0000-000000000008', 'Online Eğitim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Spor alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000059', '30000000-0000-0000-0000-000000000009', 'Fitness', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000060', '30000000-0000-0000-0000-000000000009', 'Pilates', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000061', '30000000-0000-0000-0000-000000000009', 'Yoga', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000062', '30000000-0000-0000-0000-000000000009', 'CrossFit', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000063', '30000000-0000-0000-0000-000000000009', 'Yüzme', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000064', '30000000-0000-0000-0000-000000000009', 'Tenis', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000065', '30000000-0000-0000-0000-000000000009', 'Futbol Akademisi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Finans alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000066', '30000000-0000-0000-0000-000000000010', 'Muhasebe', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000067', '30000000-0000-0000-0000-000000000010', 'Mali Müşavir', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000068', '30000000-0000-0000-0000-000000000010', 'Sigorta', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000069', '30000000-0000-0000-0000-000000000010', 'Finansal Danışman', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000070', '30000000-0000-0000-0000-000000000010', 'Yatırım Danışmanlığı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Hukuk alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000071', '30000000-0000-0000-0000-000000000011', 'Avukat', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000072', '30000000-0000-0000-0000-000000000011', 'Hukuk Bürosu', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000073', '30000000-0000-0000-0000-000000000011', 'Arabuluculuk', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Teknoloji alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000074', '30000000-0000-0000-0000-000000000012', 'SaaS', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000075', '30000000-0000-0000-0000-000000000012', 'Yapay Zeka', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000076', '30000000-0000-0000-0000-000000000012', 'Yazılım Firması', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000077', '30000000-0000-0000-0000-000000000012', 'Mobil Uygulama', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000078', '30000000-0000-0000-0000-000000000012', 'Siber Güvenlik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000079', '30000000-0000-0000-0000-000000000012', 'Bulut Hizmetleri', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Pazarlama alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000080', '30000000-0000-0000-0000-000000000013', 'Reklam Ajansı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000081', '30000000-0000-0000-0000-000000000013', 'Dijital Pazarlama', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000082', '30000000-0000-0000-0000-000000000013', 'SEO', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000083', '30000000-0000-0000-0000-000000000013', 'Sosyal Medya Ajansı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000084', '30000000-0000-0000-0000-000000000013', 'Video Prodüksiyon', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Fotoğraf & Video alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000085', '30000000-0000-0000-0000-000000000014', 'Fotoğrafçı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000086', '30000000-0000-0000-0000-000000000014', 'Düğün Fotoğrafçısı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000087', '30000000-0000-0000-0000-000000000014', 'Drone Çekimi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000088', '30000000-0000-0000-0000-000000000014', 'Video Prodüksiyon', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Etkinlik alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000089', '30000000-0000-0000-0000-000000000015', 'Düğün Organizasyonu', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000090', '30000000-0000-0000-0000-000000000015', 'Event Ajansı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000091', '30000000-0000-0000-0000-000000000015', 'Catering', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000092', '30000000-0000-0000-0000-000000000015', 'Davet Organizasyonu', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Ev & Yaşam alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000093', '30000000-0000-0000-0000-000000000016', 'İç Mimarlık', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000094', '30000000-0000-0000-0000-000000000016', 'Mimarlık', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000095', '30000000-0000-0000-0000-000000000016', 'Mobilya', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000096', '30000000-0000-0000-0000-000000000016', 'Mutfak', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000097', '30000000-0000-0000-0000-000000000016', 'Bahçe', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000098', '30000000-0000-0000-0000-000000000016', 'Perde', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- İnşaat alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000099', '30000000-0000-0000-0000-000000000017', 'Müteahhit', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000100', '30000000-0000-0000-0000-000000000017', 'Yapı Malzemeleri', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000101', '30000000-0000-0000-0000-000000000017', 'Çelik Yapı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000102', '30000000-0000-0000-0000-000000000017', 'Cephe Sistemleri', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Sanayi alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000103', '30000000-0000-0000-0000-000000000018', 'Makine', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000104', '30000000-0000-0000-0000-000000000018', 'Otomasyon', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000105', '30000000-0000-0000-0000-000000000018', 'Üretim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000106', '30000000-0000-0000-0000-000000000018', 'Fabrika', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000107', '30000000-0000-0000-0000-000000000018', 'Endüstriyel Ürünler', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Tarım alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000108', '30000000-0000-0000-0000-000000000019', 'Tarım Teknolojileri', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000109', '30000000-0000-0000-0000-000000000019', 'Gübre', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000110', '30000000-0000-0000-0000-000000000019', 'Tohum', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000111', '30000000-0000-0000-0000-000000000019', 'Hayvancılık', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000112', '30000000-0000-0000-0000-000000000019', 'Organik Ürün', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Evcil Hayvan alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000113', '30000000-0000-0000-0000-000000000020', 'Veteriner', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000114', '30000000-0000-0000-0000-000000000020', 'Pet Shop', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000115', '30000000-0000-0000-0000-000000000020', 'Pet Oteli', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000116', '30000000-0000-0000-0000-000000000020', 'Pet Kuaförü', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Anne & Bebek alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000117', '30000000-0000-0000-0000-000000000021', 'Bebek Mağazası', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000118', '30000000-0000-0000-0000-000000000021', 'Hamile Giyim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000119', '30000000-0000-0000-0000-000000000021', 'Oyuncak', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000120', '30000000-0000-0000-0000-000000000021', 'Çocuk Gelişimi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Moda alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000121', '30000000-0000-0000-0000-000000000022', 'Kadın Giyim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000122', '30000000-0000-0000-0000-000000000022', 'Erkek Giyim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000123', '30000000-0000-0000-0000-000000000022', 'Çocuk Giyim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000124', '30000000-0000-0000-0000-000000000022', 'Tesettür', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000125', '30000000-0000-0000-0000-000000000022', 'Outdoor', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000126', '30000000-0000-0000-0000-000000000022', 'Lüks Moda', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Kişisel Marka alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000127', '30000000-0000-0000-0000-000000000023', 'Eğitmen', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000128', '30000000-0000-0000-0000-000000000023', 'Koç', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000129', '30000000-0000-0000-0000-000000000023', 'Danışman', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000130', '30000000-0000-0000-0000-000000000023', 'Konuşmacı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000131', '30000000-0000-0000-0000-000000000023', 'Influencer', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000132', '30000000-0000-0000-0000-000000000023', 'İçerik Üreticisi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Kamu & STK alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000133', '30000000-0000-0000-0000-000000000024', 'Belediye', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000134', '30000000-0000-0000-0000-000000000024', 'Vakıf', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000135', '30000000-0000-0000-0000-000000000024', 'Dernek', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000136', '30000000-0000-0000-0000-000000000024', 'Eğitim Vakfı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Eğlence alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000137', '30000000-0000-0000-0000-000000000025', 'Müzik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000138', '30000000-0000-0000-0000-000000000025', 'Gece Kulübü', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000139', '30000000-0000-0000-0000-000000000025', 'Bar', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000140', '30000000-0000-0000-0000-000000000025', 'Sinema', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000141', '30000000-0000-0000-0000-000000000025', 'Tiyatro', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Dijital İçerik alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000142', '30000000-0000-0000-0000-000000000026', 'Podcast', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000143', '30000000-0000-0000-0000-000000000026', 'YouTube Kanalı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000144', '30000000-0000-0000-0000-000000000026', 'Twitch Yayıncısı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000145', '30000000-0000-0000-0000-000000000026', 'Blog', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000146', '30000000-0000-0000-0000-000000000026', 'Haber Platformu', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Girişimcilik alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000147', '30000000-0000-0000-0000-000000000027', 'Startup', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000148', '30000000-0000-0000-0000-000000000027', 'Kuluçka Merkezi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000149', '30000000-0000-0000-0000-000000000027', 'VC', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000150', '30000000-0000-0000-0000-000000000027', 'Teknoloji Girişimi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Yapay Zeka alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000151', '30000000-0000-0000-0000-000000000028', 'AI SaaS', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000152', '30000000-0000-0000-0000-000000000028', 'AI Agent', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000153', '30000000-0000-0000-0000-000000000028', 'AI Danışmanlığı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000154', '30000000-0000-0000-0000-000000000028', 'Prompt Engineering', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Kripto & Web3 alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000155', '30000000-0000-0000-0000-000000000029', 'Kripto Borsası', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000156', '30000000-0000-0000-0000-000000000029', 'Blockchain', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000157', '30000000-0000-0000-0000-000000000029', 'NFT', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000158', '30000000-0000-0000-0000-000000000029', 'DeFi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Lüks Yaşam alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000159', '30000000-0000-0000-0000-000000000030', 'Mücevher', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000160', '30000000-0000-0000-0000-000000000030', 'Saat', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000161', '30000000-0000-0000-0000-000000000030', 'Lüks Otomobil', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000162', '30000000-0000-0000-0000-000000000030', 'Premium Yaşam', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
