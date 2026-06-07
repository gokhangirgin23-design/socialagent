-- ============================================================
-- SocialAgent - V7
-- refresh_token.token kolonu için index.
-- findActiveRefreshToken sorgusu WHERE token = ? ile arama yapar;
-- index olmadan her refresh işlemi tam tablo taraması (full scan) yapar.
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumlu.
-- ============================================================

-- Refresh token değeri üzerinde lookup index'i
CREATE INDEX idx_refresh_token_token ON refresh_token (token);
