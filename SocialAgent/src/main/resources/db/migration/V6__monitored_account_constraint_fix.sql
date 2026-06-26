-- V6: user_monitored_account tablosundaki DB-level unique constraint kaldırılıyor.
-- Kullanıcı rakip hesabı silip tekrar eklediğinde inactive kayıt kalır ve
-- yeni INSERT unique constraint'e takılıyordu. Service katmanı UPDATE ile çözdü;
-- ancak DB constraint kaldırılarak kesin güvence sağlanır.
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumludur.

ALTER TABLE user_monitored_account DROP CONSTRAINT IF EXISTS uq_uma_user_monitored;
