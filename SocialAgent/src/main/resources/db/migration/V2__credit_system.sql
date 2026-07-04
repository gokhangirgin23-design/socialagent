-- FAZ CREDIT: TL bazlı cüzdandan kredi bazlı cüzdana geçiş.
-- Mevcut TL kolonları (balance, amount, balance_before/after) SİLİNMEZ; PayTR TL tutarı ve
-- tarihsel kayıt için kalır. Mevcut TL bakiyeler için otomatik dönüşüm YAPILMAZ (manuel karar).

-- user_payment: kredi bakiyesi
ALTER TABLE user_payment ADD COLUMN credit_balance     BIGINT NOT NULL DEFAULT 0;
ALTER TABLE user_payment ADD COLUMN total_credit_topup BIGINT NOT NULL DEFAULT 0;
ALTER TABLE user_payment ADD COLUMN total_credit_spent BIGINT NOT NULL DEFAULT 0;

-- user_payment_log: kredi hareketi + ürün bilgisi + paket satın alma kaydı (TEK TABLO — ayrı purchase tablosu YOK)
-- credit_amount        : TOPUP -> paket kredisi | DEBIT/REFUND -> harcanan/iade edilen kredi
-- product_type         : DEBIT satırlarında dolu (REPORT|POST|STORY|CAROUSEL)
-- package_code         : TOPUP satırlarında dolu (STARTER|STANDARD|PRO|AGENCY)
-- package_name         : satın alma anındaki paket adı (snapshot — katalog değişse de tarihsel kayıt bozulmaz)
ALTER TABLE user_payment_log ADD COLUMN credit_amount         BIGINT;
ALTER TABLE user_payment_log ADD COLUMN credit_balance_before BIGINT;
ALTER TABLE user_payment_log ADD COLUMN credit_balance_after  BIGINT;
ALTER TABLE user_payment_log ADD COLUMN product_type          VARCHAR(30);
ALTER TABLE user_payment_log ADD COLUMN package_code          VARCHAR(20);
ALTER TABLE user_payment_log ADD COLUMN package_name          VARCHAR(50);
