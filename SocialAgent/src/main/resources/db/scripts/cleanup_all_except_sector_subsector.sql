-- ============================================================
-- SocialAgent - Tüm tabloları temizleme script'i
-- sector ve subsector HARİÇ tüm tablolar silinir.
-- Sıralama, V1__init_schema.sql'deki mantıksal ilişkilere göre
-- (child -> parent) belirlenmiştir; DB-level FK yoktur ama
-- veri bütünlüğü açısından bu sıra korunmalıdır.
-- NOT: Flyway migration klasöründe DEĞİLDİR (db/scripts), otomatik
-- çalışmaz; elle (psql/H2 console) çalıştırılır.
-- ============================================================

-- 1. En alt seviye (başka hiçbir tablo tarafından referans edilmeyen) tablolar
DELETE FROM content_request;
DELETE FROM user_payment_log;
DELETE FROM notification;

-- 2. social_post'a bağlı analiz sonuçları
DELETE FROM post_analysis;

-- 3. report_request'e bağlı rapor (content_request/user_payment_log/notification report_id/reference_id ile buna bağlıydı)
DELETE FROM report;

-- 4. report_request'e bağlı postlar
DELETE FROM social_post;

-- 5. user_payment_log'a bağlı cüzdan
DELETE FROM user_payment;

-- 6. rapor isteği (social_post, report, user_payment_log tarafından referans edildi)
DELETE FROM report_request;

-- 7. kullanıcının kendi sosyal hesabı (report_request.selected_user_social_account_id tarafından referans edildi)
DELETE FROM user_social_account;

-- 8. refresh token
DELETE FROM refresh_token;

-- 9. en son: kullanıcı (yukarıdaki tüm tablolar tarafından referans edildi)
DELETE FROM user_info;

-- sector ve subsector kasıtlı olarak silinmedi.
