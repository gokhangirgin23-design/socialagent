-- ============================================================
-- V8 — Sektör/Alt sektör listesi küçültme + Moda'ya Takı ekleme
-- ============================================================
-- Karar (Gökhan onayı, 2026-07-11): 30 sektörden Türkiye'de Instagram üzerinden en çok
-- satış/hizmet yapılan, tüketici odaklı 16 tanesi tutuluyor; kalan 14'ü (B2B/niş/doğrudan
-- "satış" odaklı olmayan) siliniyor. Zaten bu sektörlerden birini seçmiş kullanıcılar
-- (varsa) için bilinçli olarak bir taşıma/reassignment yapılmıyor — user_info.sector_id/
-- subsector_id için DB seviyesinde FK constraint yok (CLAUDE.md Madde 6), bu yüzden bu
-- silme işlemi hata fırlatmaz; yalnızca o kullanıcıların sektör bağlamı boş dönmeye başlar
-- (loadUserSectorContext vb. sorgular join'lenemediği için null döner, pipeline çökmez).
--
-- SİLİNENLER (14): Eğitim, Finans, Pazarlama, Sanayi, Tarım, Anne & Bebek, Kişisel Marka,
-- Kamu & STK, Eğlence, Dijital İçerik, Girişimcilik, Yapay Zeka, Kripto & Web3, Lüks Yaşam.
--
-- TUTULANLAR (16): E-Ticaret, Sağlık, Güzellik, Yeme & İçme, Otel & Turizm, Gayrimenkul,
-- Otomotiv, Spor, Hukuk, Teknoloji, Fotoğraf & Video, Etkinlik, Ev & Yaşam, İnşaat,
-- Evcil Hayvan, Moda — TUTULAN sektörlerin alt sektör listeleri bu migration'da
-- DEĞİŞTİRİLMEDİ (yalnızca Moda'ya "Takı" eklendi, aşağıya bakınız).
-- ============================================================

-- Silinecek sektörlerin alt sektörleri (önce çocuk kayıtlar)
DELETE FROM subsector WHERE sector_id IN (
    '30000000-0000-0000-0000-000000000008', -- Eğitim
    '30000000-0000-0000-0000-000000000010', -- Finans
    '30000000-0000-0000-0000-000000000013', -- Pazarlama
    '30000000-0000-0000-0000-000000000018', -- Sanayi
    '30000000-0000-0000-0000-000000000019', -- Tarım
    '30000000-0000-0000-0000-000000000021', -- Anne & Bebek
    '30000000-0000-0000-0000-000000000023', -- Kişisel Marka
    '30000000-0000-0000-0000-000000000024', -- Kamu & STK
    '30000000-0000-0000-0000-000000000025', -- Eğlence
    '30000000-0000-0000-0000-000000000026', -- Dijital İçerik
    '30000000-0000-0000-0000-000000000027', -- Girişimcilik
    '30000000-0000-0000-0000-000000000028', -- Yapay Zeka
    '30000000-0000-0000-0000-000000000029', -- Kripto & Web3
    '30000000-0000-0000-0000-000000000030'  -- Lüks Yaşam
);

-- Silinecek sektörlerin kendisi
DELETE FROM sector WHERE sector_id IN (
    '30000000-0000-0000-0000-000000000008',
    '30000000-0000-0000-0000-000000000010',
    '30000000-0000-0000-0000-000000000013',
    '30000000-0000-0000-0000-000000000018',
    '30000000-0000-0000-0000-000000000019',
    '30000000-0000-0000-0000-000000000021',
    '30000000-0000-0000-0000-000000000023',
    '30000000-0000-0000-0000-000000000024',
    '30000000-0000-0000-0000-000000000025',
    '30000000-0000-0000-0000-000000000026',
    '30000000-0000-0000-0000-000000000027',
    '30000000-0000-0000-0000-000000000028',
    '30000000-0000-0000-0000-000000000029',
    '30000000-0000-0000-0000-000000000030'
);

-- Moda (30000000-...-0022) altına yeni alt sektör: Takı
-- (mevcut alt sektörler: Kadın Giyim, Erkek Giyim, Çocuk Giyim, Tesettür, Outdoor, Lüks Moda —
--  Takı hiç yoktu; E-Ticaret sektöründeki "Takı & Aksesuar" ayrı ve dokunulmadı)
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000200', '30000000-0000-0000-0000-000000000022', 'Takı', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
