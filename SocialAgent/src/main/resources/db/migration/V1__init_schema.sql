-- ============================================================
-- SocialAgent - V1 İlk şema (CLAUDE.md Bölüm 5)
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumludur.
-- Ortak alanlar: active (0/1), created_date, updated_date. Tüm PK'ler UUID.
-- İlişkiler native sorgu ile çekildiğinden FK constraint yok; index'ler var.
-- NOT: Bu dosya eski V1-V13 migration'larının tek şema/tohum halinde
-- birleştirilmiş (squash) hâlidir; eski dosyalar artık yoktur
-- (spectiqs-gelistirme-paketi Ek Görev — konsolidasyon, 2026-07-14).
-- ============================================================

-- ============================================================
-- ŞEMA
-- ============================================================

-- Sektör tablosu (display_order: select listelerindeki gösterim sırası, küçük önce — eski V13)
CREATE TABLE sector (
    sector_id      UUID PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    active         INTEGER      NOT NULL DEFAULT 1,
    display_order  INTEGER      NOT NULL DEFAULT 999,
    created_date   TIMESTAMP,
    updated_date   TIMESTAMP
);

-- Alt sektör tablosu (display_order: bağlı sektör içindeki gösterim sırası — eski V13)
CREATE TABLE subsector (
    subsector_id   UUID PRIMARY KEY,
    sector_id      UUID         NOT NULL,
    name           VARCHAR(255) NOT NULL,
    active         INTEGER      NOT NULL DEFAULT 1,
    display_order  INTEGER      NOT NULL DEFAULT 999,
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
--
-- credit_debited/credit_debit_error/credit_debit_attempts: kredi düşümü mutabakatı (eski V6) —
--   COMPLETED sonrası kredi başarıyla düşüldü mü (0/1); admin reconciliation bu kolonlar üzerinden
--   COMPLETED+credit_debited=0 kayıtları bulup tekrar dener.
-- active_lock_key: yalnızca PENDING/PROCESSING iken userId taşır, terminal durumda NULL'a döner
--   (eski V7, E7 çift-tık fix'i) — UNIQUE kısıt "kullanıcı başına en fazla 1 aktif istek" kuralını
--   DB seviyesinde uygular (NULL'lar kısıttan muaf). Constraint adı ReportRequestService'te
--   DataIntegrityViolationException mesajı eşleştirmesi için birebir korunmalıdır.
-- sector_id/subsector_id: rapor OLUŞTURULDUĞU ANDAKİ sektör/alt sektör (eski V10) — kullanıcı
--   sonradan sektör değiştirirse eski raporlar yanlış görünmesin diye canlı user_info'ya değil bu
--   anlık kopyaya göre gösterilir.
-- is_free_usage: bu rapor ücretsiz ilk kullanım hakkıyla mı oluşturuldu (eski V11).
-- own_account_name: rapor OLUŞTURULDUĞU ANDAKİ kendi hesap adı (eski V12) — AccountService hesabı
--   yerinde yeniden adlandırabildiğinden, canlı join yerine bu STRING snapshot kullanılır.
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
    credit_debited                   SMALLINT NOT NULL DEFAULT 0,
    credit_debit_error               TEXT,
    credit_debit_attempts            INTEGER  NOT NULL DEFAULT 0,
    active_lock_key                  UUID,
    sector_id                        UUID,
    subsector_id                     UUID,
    is_free_usage                    INTEGER NOT NULL DEFAULT 0,
    own_account_name                 VARCHAR(255),
    active                           INTEGER NOT NULL DEFAULT 1,
    created_date                     TIMESTAMP,
    updated_date                     TIMESTAMP,
    CONSTRAINT uq_report_request_active_lock UNIQUE (active_lock_key)
);
CREATE INDEX idx_report_request_user_id ON report_request (user_id);
-- Sweep/requeue sorgusu status üzerinden filtrelediği için index
CREATE INDEX idx_report_request_status ON report_request (status);
-- Reconciliation sorgusu (status + credit_debited üzerinden) için index
CREATE INDEX idx_report_request_credit_debited ON report_request (status, credit_debited);

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
-- Bakiye / Cüzdan + Hareket Defteri (ödeme) — FAZ CREDIT (eski V2) dahil
-- ============================================================

-- Kullanıcı cüzdanı (kullanıcı başına TEK satır)
-- credit_balance/total_credit_topup/total_credit_spent: kredi bazlı cüzdan (eski V2) — TL
-- kolonları (balance/total_topup/total_spent) SİLİNMEDİ, PayTR TL tutarı ve tarihsel kayıt için
-- kalır; mevcut TL bakiyeler için otomatik dönüşüm yapılmaz.
CREATE TABLE user_payment (
    id                   UUID PRIMARY KEY,
    user_id              UUID NOT NULL,
    balance              NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency             VARCHAR(3) NOT NULL DEFAULT 'TL',
    total_topup          NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_spent          NUMERIC(19, 2) NOT NULL DEFAULT 0,
    credit_balance       BIGINT NOT NULL DEFAULT 0,
    total_credit_topup   BIGINT NOT NULL DEFAULT 0,
    total_credit_spent   BIGINT NOT NULL DEFAULT 0,
    version              BIGINT NOT NULL DEFAULT 0,
    active               INTEGER NOT NULL DEFAULT 1,
    created_date         TIMESTAMP,
    updated_date         TIMESTAMP,
    CONSTRAINT uq_user_payment_user UNIQUE (user_id)
);

-- Para hareketi + PayTR işlem kaydı (N satır)
-- transaction_type: TOPUP | DEBIT | REFUND
-- payment_status  : INITIATED | PENDING | SUCCESS | FAILED | EXPIRED
-- merchant_oid    : PayTR sipariş no (idempotensi anahtarı, UNIQUE)
-- processed       : bakiye bu satır için işlendi mi (0/1)
-- pending_*       : ödeme tamamlanınca oluşturulacak rapor isteğinin niyeti (deficit akışı)
-- report_id       : ödeme tamamlanıp rapor üretilince ScrapePipelineService tarafından doldurulur
-- amount/currency : NOT NULL DEĞİL (eski V4/V5) — DEBIT/REFUND kredi hareketlerinde TL tutarı/para
--   birimi kavramı yok; writeCreditMovementLog() bu alanları hiç set etmez, Hibernate INSERT'i tüm
--   mapped kolonları açıkça (null dahil) gönderdiğinden DB DEFAULT'u devreye girmez.
-- credit_amount/credit_balance_before/after/product_type/package_code/package_name: kredi hareketi
--   + ürün/paket bilgisi (eski V2) — ayrı bir "purchase" tablosu yok, TEK tabloda tutulur.
CREATE TABLE user_payment_log (
    id                          UUID PRIMARY KEY,
    user_id                     UUID NOT NULL,
    user_payment_id             UUID NOT NULL,
    report_request_id           UUID,
    report_id                   UUID,
    transaction_type            VARCHAR(20) NOT NULL,
    amount                      NUMERIC(19, 2),
    currency                    VARCHAR(3),
    balance_before              NUMERIC(19, 2),
    balance_after               NUMERIC(19, 2),
    credit_amount                BIGINT,
    credit_balance_before        BIGINT,
    credit_balance_after         BIGINT,
    product_type                 VARCHAR(30),
    package_code                 VARCHAR(20),
    package_name                 VARCHAR(50),
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
-- credit_debited/credit_debit_error/credit_debit_attempts: kredi düşümü mutabakatı (eski V6).
-- is_free_usage: bu içerik ücretsiz ilk kullanım hakkıyla mı oluşturuldu (eski V11).
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
    visual_urls              TEXT,                              -- JSON array; S3 URL'leri
    caption                  TEXT,
    hashtags                 TEXT,
    cta                      TEXT,
    first_comment            TEXT,
    suggested_post_time      TEXT,
    process_started_date     TIMESTAMP,
    process_finished_date    TIMESTAMP,
    process_error            TEXT,
    attempt_count            INTEGER         NOT NULL DEFAULT 0,
    credit_debited            SMALLINT        NOT NULL DEFAULT 0,
    credit_debit_error        TEXT,
    credit_debit_attempts     INTEGER         NOT NULL DEFAULT 0,
    is_free_usage             INTEGER         NOT NULL DEFAULT 0,
    active                    SMALLINT        NOT NULL DEFAULT 1,
    created_date              TIMESTAMP,
    updated_date               TIMESTAMP
);
CREATE INDEX idx_content_request_user   ON content_request(user_id);
CREATE INDEX idx_content_request_report ON content_request(report_id);
CREATE INDEX idx_content_request_status ON content_request(status);
CREATE INDEX idx_content_request_credit_debited ON content_request (status, credit_debited);

-- Ücretsiz ilk kullanım hakkı (eski V11) — kullanıcı başına 1 rapor + (o rapora sıralı bağlı)
-- 1 post/story hakkı. Kredi sistemine (user_payment.credit_balance) HİÇ dokunmaz; kontrol/kayıt
-- tamamen bu tablodan yapılır (bkz. FreeUsageService).
CREATE TABLE user_free_usage (
    user_id                 UUID PRIMARY KEY,
    free_report_used        INTEGER NOT NULL DEFAULT 0,
    free_report_request_id  UUID,
    free_report_used_date   TIMESTAMP,
    free_content_used       INTEGER NOT NULL DEFAULT 0,
    free_content_id         UUID,
    free_content_used_date  TIMESTAMP,
    created_date            TIMESTAMP NOT NULL,
    updated_date            TIMESTAMP NOT NULL
);

-- Mevcut tüm kullanıcılar için satır seed et (herkese 1 hak tanı kararı — eski V11). Fresh/boş
-- şemada user_info boş olduğundan bu sorgu 0 satır ekler; kalıcı bir "eski kullanıcıları migrate
-- et" adımı olarak tutuluyor (ör. ileride bu V1'in üstüne veri geri yüklenirse).
INSERT INTO user_free_usage (user_id, free_report_used, free_content_used, created_date, updated_date)
SELECT user_id, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM user_info;

-- ============================================================
-- TOHUM VERİSİ — Sektörler + Alt sektörler (sabit UUID; her ortamda aynı)
-- Güncel kürasyon (eski V13): 15 sektör / 87 alt sektör, verilen İŞ SIRASIYLA
-- (display_order). Eski 30/162'lik liste + Influencer eki (eski V3) + V8/V9
-- kürasyonları bu liste tarafından tamamen değiştirildi (superseded).
-- ============================================================

-- Sektörler (15, verilen sırayla)
INSERT INTO sector (sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('11111111-1111-1111-1111-111111111201', 'Moda & Aksesuar', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111202', 'Güzellik & Sağlık', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111203', 'Yeme & İçme', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111204', 'Anne & Bebek', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111205', 'Ev Yaşam & Dekorasyon', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111206', 'Eğitim', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111207', 'Otomotiv', 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111208', 'Evcil Hayvan', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111209', 'Yazılım', 1, 9, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111210', 'Gayrimenkul', 1, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111211', 'Turizm & Etkinlik', 1, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111212', 'Finans', 1, 12, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111213', 'Hukuk', 1, 13, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111214', 'Profesyonel Hizmetler', 1, 14, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('11111111-1111-1111-1111-111111111215', 'Pazarlama, Medya', 1, 15, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Moda & Aksesuar alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222201', '11111111-1111-1111-1111-111111111201', 'Kadın Giyim', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222202', '11111111-1111-1111-1111-111111111201', 'Erkek Giyim', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222203', '11111111-1111-1111-1111-111111111201', 'Çocuk Giyim', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222204', '11111111-1111-1111-1111-111111111201', 'Tesettür Giyim', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222205', '11111111-1111-1111-1111-111111111201', 'Ayakkabı', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222206', '11111111-1111-1111-1111-111111111201', 'Çanta', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222207', '11111111-1111-1111-1111-111111111201', 'Takı', 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222208', '11111111-1111-1111-1111-111111111201', 'Saat', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222209', '11111111-1111-1111-1111-111111111201', 'İç Giyim', 1, 9, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222210', '11111111-1111-1111-1111-111111111201', 'Spor Giyim', 1, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Güzellik & Sağlık alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222211', '11111111-1111-1111-1111-111111111202', 'Kuaför', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222212', '11111111-1111-1111-1111-111111111202', 'Berber', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222213', '11111111-1111-1111-1111-111111111202', 'Güzellik Salonu', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222214', '11111111-1111-1111-1111-111111111202', 'Nail Studio', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222215', '11111111-1111-1111-1111-111111111202', 'Diş Kliniği', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222216', '11111111-1111-1111-1111-111111111202', 'Estetik Cerrahi', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222217', '11111111-1111-1111-1111-111111111202', 'Saç Ekimi', 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222218', '11111111-1111-1111-1111-111111111202', 'Diyetisyen', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222219', '11111111-1111-1111-1111-111111111202', 'Psikolog', 1, 9, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222220', '11111111-1111-1111-1111-111111111202', 'Spor Salonu', 1, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222221', '11111111-1111-1111-1111-111111111202', 'Pilates', 1, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111202', 'Yoga', 1, 12, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Yeme & İçme alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222223', '11111111-1111-1111-1111-111111111203', 'Pastane', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222224', '11111111-1111-1111-1111-111111111203', 'Fırın', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222225', '11111111-1111-1111-1111-111111111203', 'Burger', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222226', '11111111-1111-1111-1111-111111111203', 'Pizza', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222227', '11111111-1111-1111-1111-111111111203', 'Kebap', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222228', '11111111-1111-1111-1111-111111111203', 'Döner', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222229', '11111111-1111-1111-1111-111111111203', 'Tatlıcı', 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222230', '11111111-1111-1111-1111-111111111203', 'Kahvaltıcı', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222231', '11111111-1111-1111-1111-111111111203', 'Restoran', 1, 9, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222232', '11111111-1111-1111-1111-111111111203', 'Kafe', 1, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222233', '11111111-1111-1111-1111-111111111203', 'Nargile Kafe', 1, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222234', '11111111-1111-1111-1111-111111111203', 'Kahveci', 1, 12, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Anne & Bebek alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222235', '11111111-1111-1111-1111-111111111204', 'Bebek Ürünleri', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222236', '11111111-1111-1111-1111-111111111204', 'Oyuncak', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222237', '11111111-1111-1111-1111-111111111204', 'Hamile Ürünleri', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222238', '11111111-1111-1111-1111-111111111204', 'Çocuk Etkinlik Merkezi', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Ev Yaşam & Dekorasyon alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222239', '11111111-1111-1111-1111-111111111205', 'Mobilya', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222240', '11111111-1111-1111-1111-111111111205', 'Ev Tekstili', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222241', '11111111-1111-1111-1111-111111111205', 'Dekorasyon', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222242', '11111111-1111-1111-1111-111111111205', 'Mutfak Ürünleri', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222243', '11111111-1111-1111-1111-111111111205', 'İç Aydınlatma', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222244', '11111111-1111-1111-1111-111111111205', 'Bahçe Ürünleri', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222245', '11111111-1111-1111-1111-111111111205', 'Ev Elektronik Eşyalar', 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Eğitim alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222246', '11111111-1111-1111-1111-111111111206', 'Online Eğitim', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222247', '11111111-1111-1111-1111-111111111206', 'Dil Kursu', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222248', '11111111-1111-1111-1111-111111111206', 'Yazılım Eğitimi', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222249', '11111111-1111-1111-1111-111111111206', 'Özel Okul', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222250', '11111111-1111-1111-1111-111111111206', 'Kreş', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222251', '11111111-1111-1111-1111-111111111206', 'Üniversite', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Otomotiv alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222252', '11111111-1111-1111-1111-111111111207', 'Oto Galeri', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222253', '11111111-1111-1111-1111-111111111207', 'Oto Servis', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222254', '11111111-1111-1111-1111-111111111207', 'Araç Kiralama', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Evcil Hayvan alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222255', '11111111-1111-1111-1111-111111111208', 'Pet Shop', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222256', '11111111-1111-1111-1111-111111111208', 'Veteriner', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Yazılım alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222257', '11111111-1111-1111-1111-111111111209', 'Yazılım Firması', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222258', '11111111-1111-1111-1111-111111111209', 'Mobil Uygulama', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222259', '11111111-1111-1111-1111-111111111209', 'Yapay Zeka', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Gayrimenkul alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222260', '11111111-1111-1111-1111-111111111210', 'Emlak Ofisi', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222261', '11111111-1111-1111-1111-111111111210', 'Konut Projesi', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222262', '11111111-1111-1111-1111-111111111210', 'Villa Projesi', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222263', '11111111-1111-1111-1111-111111111210', 'Mimarlık', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222264', '11111111-1111-1111-1111-111111111210', 'İç Mimarlık', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Turizm & Etkinlik alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222265', '11111111-1111-1111-1111-111111111211', 'Otel', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222266', '11111111-1111-1111-1111-111111111211', 'Butik Otel', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222267', '11111111-1111-1111-1111-111111111211', 'Kamp', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222268', '11111111-1111-1111-1111-111111111211', 'Seyahat Acentesi', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222269', '11111111-1111-1111-1111-111111111211', 'Organizasyon', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222270', '11111111-1111-1111-1111-111111111211', 'Düğün Organizasyonu', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222271', '11111111-1111-1111-1111-111111111211', 'Fotoğrafçı', 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222272', '11111111-1111-1111-1111-111111111211', 'Video Prodüksiyon', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Finans alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222273', '11111111-1111-1111-1111-111111111212', 'Sigorta', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222274', '11111111-1111-1111-1111-111111111212', 'BES', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222275', '11111111-1111-1111-1111-111111111212', 'Yatırım', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222276', '11111111-1111-1111-1111-111111111212', 'Muhasebe', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222277', '11111111-1111-1111-1111-111111111212', 'Mali Müşavir', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Hukuk alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222278', '11111111-1111-1111-1111-111111111213', 'Avukat', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222279', '11111111-1111-1111-1111-111111111213', 'Arabulucu', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222280', '11111111-1111-1111-1111-111111111213', 'Uzlaştırmacı', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Profesyonel Hizmetler alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222281', '11111111-1111-1111-1111-111111111214', 'Danışmanlık', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222282', '11111111-1111-1111-1111-111111111214', 'İnsan Kaynakları', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Pazarlama, Medya alt sektörleri
INSERT INTO subsector (subsector_id, sector_id, name, active, display_order, created_date, updated_date) VALUES
 ('22222222-2222-2222-2222-222222222283', '11111111-1111-1111-1111-111111111215', 'Dijital Pazarlama', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222284', '11111111-1111-1111-1111-111111111215', 'Reklam Ajansı', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222285', '11111111-1111-1111-1111-111111111215', 'Influencer', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222286', '11111111-1111-1111-1111-111111111215', 'İçerik Üreticisi', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('22222222-2222-2222-2222-222222222287', '11111111-1111-1111-1111-111111111215', 'Haber Medyası', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
