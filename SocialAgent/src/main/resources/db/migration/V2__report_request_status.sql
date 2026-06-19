-- V2: report_request iş (job) yaşam döngüsü durumu
-- queue_pushed / queue_error  -> KUYRUĞA BASMA aşamasını izler (mevcut).
-- status                      -> İŞLEME aşamasını izler (yeni).
--
-- Durum akışı: PENDING -> PROCESSING -> COMPLETED | PARTIAL | FAILED
--   PENDING    : oluşturuldu/kuyrukta, worker henüz işlemedi
--   PROCESSING : worker işliyor (process_started_date set)
--   COMPLETED  : rapor üretildi VE tüm postlar analizli (analyzed == total)
--   PARTIAL    : rapor üretildi AMA eksik analiz var (yutulmuş dış-API hatası; analyzed < total)
--   FAILED     : işleme sırasında exception kaçtı / kullanılabilir rapor üretilemedi
-- attempt_count: requeue/sweep deneme sayacı (poison-message koruması; tavanı aşan istek tekrar seçilmez)
--
-- H2 (local, MODE=PostgreSQL) ve PostgreSQL (test/prod) ile uyumludur.

ALTER TABLE report_request ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE report_request ADD COLUMN process_started_date  TIMESTAMP;
ALTER TABLE report_request ADD COLUMN process_finished_date TIMESTAMP;
ALTER TABLE report_request ADD COLUMN process_error TEXT;
ALTER TABLE report_request ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;

-- Sweep/requeue sorgusu status üzerinden filtrelediği için index
CREATE INDEX idx_report_request_status ON report_request (status);
