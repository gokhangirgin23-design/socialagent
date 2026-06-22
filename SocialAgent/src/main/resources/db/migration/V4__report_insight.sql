-- V4: report tablosuna insight_json kolonu eklendi (dashboard structured kart için).
-- Boş geçilebilir: eski raporlarda insight yoktur, frontend boş-durum gösterir.
ALTER TABLE report ADD COLUMN IF NOT EXISTS insight_json TEXT;
