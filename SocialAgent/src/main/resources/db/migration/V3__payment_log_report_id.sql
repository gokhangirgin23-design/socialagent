-- V3: user_payment_log tablosuna rapor bağlantısı
-- report_id: ödeme tamamlanıp rapor üretilince ScrapePipelineService tarafından doldurulur.
-- Mevcut satırlar için NULL kalır; geçmişe dönük doldurmaya gerek yok.
ALTER TABLE user_payment_log ADD COLUMN report_id UUID;
CREATE INDEX idx_upl_report_id ON user_payment_log (report_id);
