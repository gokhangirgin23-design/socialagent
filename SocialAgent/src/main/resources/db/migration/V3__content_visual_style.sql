-- İçerik üretiminde görsel stil seçimi: "Premium Üret" / "Doğal Üret". Default: PREMIUM.
ALTER TABLE content_request ADD COLUMN visual_style VARCHAR(16) NOT NULL DEFAULT 'PREMIUM';
