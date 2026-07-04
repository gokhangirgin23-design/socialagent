-- ============================================================
-- user_payment_log.amount NOT NULL kısıtını kaldırır.
--
-- Kök neden: V1'de "amount" (TL tutarı) sadece PayTR akışı için NOT NULL
-- tanımlanmıştı. V2'de kredi sistemi eklenince DEBIT/REFUND hareketleri
-- writeCreditMovementLog() ile yazılırken amount hiç set edilmiyor (bu
-- işlemlerde TL tutarı yok, sadece credit_amount var) — bu da her
-- DEBIT/REFUND insert'inde "null value in column amount" NOT NULL ihlaline
-- ve debitOnCompleted()/refund akışlarındaki try/catch tarafından sessizce
-- yutulan bir hataya yol açıyor (kredi hiç düşmüyor/iade edilmiyor).
-- ============================================================

ALTER TABLE user_payment_log ALTER COLUMN amount DROP NOT NULL;
