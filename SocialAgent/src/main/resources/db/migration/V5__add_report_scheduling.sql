-- ============================================================
-- SocialAgent - V5 (FAZ 7 - Rapor + iş sonu muhasebesi)
-- Periyot bazlı yeniden zamanlama için sonraki çalışma zamanı kolonu.
-- next_run_date:
--   NULL                -> hemen uygun (mevcut job'lar ve ON_DEMAND ilk çalışma)
--   gelecek bir zaman    -> recurring job; o zamana kadar scheduler tarafından alınmaz
-- JobCompletionService bir çalışma bitince periyot kadar (DAILY/WEEKLY/MONTHLY) ileri set eder;
-- JobScheduler tarama sorgusu "next_run_date IS NULL OR next_run_date <= CURRENT_TIMESTAMP" filtresini ekler.
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumlu.
-- ============================================================

-- Sonraki çalışma zamanı (nullable; NULL = hemen uygun)
ALTER TABLE user_job ADD COLUMN next_run_date TIMESTAMP;

-- Scheduler zamanlama filtresi için index
CREATE INDEX idx_user_job_next_run ON user_job (next_run_date);
