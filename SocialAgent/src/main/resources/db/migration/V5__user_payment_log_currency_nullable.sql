-- ============================================================
-- user_payment_log.currency NOT NULL kısıtını kaldırır.
--
-- V4 ile aynı kök neden: writeCreditMovementLog() (DEBIT/REFUND) currency
-- alanını hiç set etmiyor (kredi hareketlerinde TL para birimi kavramı
-- yok). "DEFAULT 'TL'" tanımlı olsa da Hibernate INSERT'i tüm mapped
-- kolonları açıkça (null dahil) gönderdiğinden DB varsayılanı devreye
-- girmiyor ve NOT NULL ihlali oluşuyor.
-- ============================================================

ALTER TABLE user_payment_log ALTER COLUMN currency DROP NOT NULL;
