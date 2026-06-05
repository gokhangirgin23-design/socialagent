-- ============================================================
-- SocialAgent - V6 (FAZ 8 - Bildirim & Dashboard)
-- Bildirim listeleme ve "okunmamış" sorgularını hızlandıran composite index'ler.
--   - listNotifications: WHERE user_id = ? [AND is_read = 0] ORDER BY created_date DESC
--   - unreadCount      : WHERE user_id = ? AND is_read = 0
-- Rapor dashboard sorguları mevcut index'leri kullanır:
--   report.idx_report_job_id (V1) + user_job.idx_user_job_user_id (V1) join için yeterli.
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumlu.
-- ============================================================

-- Kullanıcı bazlı sıralı listeleme (user_id + created_date)
CREATE INDEX idx_notification_user_created ON notification (user_id, created_date);

-- Okunmamış filtreleme/sayım (user_id + is_read)
CREATE INDEX idx_notification_user_read ON notification (user_id, is_read);
