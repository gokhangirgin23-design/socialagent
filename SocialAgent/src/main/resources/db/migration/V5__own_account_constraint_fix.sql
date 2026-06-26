-- V5: user_social_account tablosundaki DB-level unique constraint kaldırılıyor.
-- Benzersizlik kontrolü service katmanında (active=1 filtreli native sorgu) yapılmakta.
-- DB constraint pasif kayıtları (active=0) da kapsayarak hesap değiştirmeyi engelliyordu.
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumludur.

ALTER TABLE user_social_account DROP CONSTRAINT IF EXISTS uq_usa_user_platform_account;
