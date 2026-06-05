-- ============================================================
-- SocialAgent - V3 (FAZ 4 - Scheduler + RabbitMQ)
-- Scheduler'ın idempotent (tek sefer) kuyruğa basması için kuyruklama izleme kolonları.
-- queued: 0 = henüz kuyruğa basılmadı, 1 = kuyruğa basıldı (claim edildi)
-- Çift instance (app1/app2) yarışı, scheduler'daki atomik şartlı UPDATE ile çözülür.
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumlu.
-- ============================================================

-- Kuyruğa basıldı bayrağı (varsayılan 0 = basılmadı)
ALTER TABLE user_job ADD COLUMN queued INTEGER NOT NULL DEFAULT 0;

-- Kuyruğa basılma zamanı (claim anında set edilir, nullable)
ALTER TABLE user_job ADD COLUMN queued_date TIMESTAMP;

-- Scheduler tarama sorgusu (active=1 AND completed=0 AND queued=0) için index
CREATE INDEX idx_user_job_queue_scan ON user_job (active, completed, queued);
