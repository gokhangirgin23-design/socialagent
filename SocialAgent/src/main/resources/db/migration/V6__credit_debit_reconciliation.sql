-- Kredi düşümü ile rapor/içerik teslimi ayrı transaction'larda gerçekleştiği için (Apify/AI
-- çağrıları dakikalarca sürebilir, tek transaction'da tutulamaz), COMPLETED sonrası kredi
-- düşümü sırasında oluşan hata daha önce sadece log'a yazılıp yutuluyordu; ürün teslim
-- edilmiş ama kredi düşmemiş oluyordu ve bu durum hiçbir yerde kalıcı olarak izlenmiyordu.
-- Bu kolonlar düşüm sonucunu kalıcı olarak işaretler; admin reconciliation (retry) bu
-- kolonlar üzerinden COMPLETED+credit_debited=0 kayıtları bulup tekrar dener.

ALTER TABLE report_request ADD COLUMN credit_debited        SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE report_request ADD COLUMN credit_debit_error    TEXT;
ALTER TABLE report_request ADD COLUMN credit_debit_attempts INTEGER  NOT NULL DEFAULT 0;

ALTER TABLE content_request ADD COLUMN credit_debited        SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE content_request ADD COLUMN credit_debit_error    TEXT;
ALTER TABLE content_request ADD COLUMN credit_debit_attempts INTEGER  NOT NULL DEFAULT 0;

-- Reconciliation sorgusu (status + credit_debited üzerinden) için index
CREATE INDEX idx_report_request_credit_debited  ON report_request  (status, credit_debited);
CREATE INDEX idx_content_request_credit_debited ON content_request (status, credit_debited);
