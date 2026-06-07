-- social_post tablosuna Apify ham JSON kolonu eklenir (WorkerPrompt — result_json)
-- H2 (MODE=PostgreSQL) ve PostgreSQL uyumlu. TEXT tipi her iki motorla çalışır.
ALTER TABLE social_post ADD COLUMN result_json TEXT;
