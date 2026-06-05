-- ============================================================
-- SocialAgent - V4 (FAZ 5 - Worker + Apify)
-- Tekrar-analiz koruması ve sektör hesap sorguları için yardımcı index'ler.
-- Sektör top-5 hedeflerinde social_post (platform_sector, account_name_sector) ile
-- kimlik eşleşmesi yapılır; bu birleşik index o JOIN'i hızlandırır.
-- (platform, platform_post_id) zaten V1'de UNIQUE -> dedup sorgusu index'li.
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumlu.
-- ============================================================

-- Sektör hesabı bazlı tekrar-analiz JOIN'i için birleşik index
CREATE INDEX idx_social_post_sector ON social_post (platform_sector, account_name_sector);
