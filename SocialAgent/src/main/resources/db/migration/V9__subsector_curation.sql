-- ============================================================
-- V9 — Alt sektör listesi sadeleştirme (Gökhan onayı, 2026-07-11/12)
-- ============================================================
-- V8'de sektör listesi küçültülmüştü (30→16 + Influencer); bu migration aynı gözden
-- geçirmeyi ALT SEKTÖR seviyesinde yapıyor. V8'den farklı olarak bu değişikliğin gerekçesi
-- Apify arama havuzu DEĞİL (arama zaten TargetResolver.loadUserSectorKeyword ile sadece
-- SEKTÖR adını kullanıyor, alt sektör aramaya hiç girmiyor) — yalnızca UI sadeliği ve
-- kategori doğruluğu. Değiştirilen 4 sektör dışındaki 13 sektörün (+Influencer) alt sektör
-- listelerine dokunulmadı.
--
-- Migration öncesi kontrol edildi: aşağıda silinen 11 alt sektörden HİÇBİRİNİ şu an seçmiş
-- kullanıcı yok (test DB, doğrudan sorgu ile doğrulandı) — V8'deki gibi taşıma/reassignment
-- yapılmıyor, gerek de yok.
--
-- 1) SAĞLIK — "Veteriner" kaldırıldı: insan sağlığı alt sektörleri (Diş Kliniği, Diyetisyen,
--    Estetik Merkezi, Fizyoterapi, Göz Kliniği, Kadın Doğum, Psikolog, Tıp Merkezi) arasında
--    yanlışlıkla bulunan bir hayvan sağlığı kategorisiydi — Veteriner zaten kendi başına
--    EVCİL HAYVAN sektörünün alt sektörü (40000000-...-0113, BUNA DOKUNULMADI).
-- 2) GAYRİMENKUL — "İnşaat Firması" kaldırıldı: ayrı bir sektör olan İNŞAAT'ın "Müteahhit"
--    alt sektörüyle örtüşüyordu, hangi sektöre gireceği belirsizlik yaratıyordu.
-- 3) YEME & İÇME — "Hamburger", "Pizza", "Steakhouse" kaldırıldı: zaten var olan "Fast Food"
--    ve "Restoran" alt sektörlerinin kapsadığı, aşırı bölünmüş alt kategorilerdi.
-- 4) TEKNOLOJİ — B2B/yazılım şirketi odaklı 6 alt sektörün tamamı ("Bulut Hizmetleri",
--    "Mobil Uygulama", "SaaS", "Siber Güvenlik", "Yapay Zeka", "Yazılım Firması") kaldırıldı —
--    Instagram'da tüketiciye doğrudan satış/hizmet yapan hesap profiliyle uyuşmuyorlardı
--    (V8'de "Yapay Zeka" ayrı bir SEKTÖR olarak bu gerekçeyle tamamen silinmişti). Yerine
--    tüketiciye yakın 4 yeni alt sektör eklendi: "Teknoloji Mağazası", "Aksesuar Mağazası",
--    "Teknik Servis", "Akıllı Ev Ürünleri".
-- ============================================================

-- 1) Sağlık — yanlış yerleştirilmiş Veteriner (Evcil Hayvan'daki 0113'e DOKUNULMUYOR)
DELETE FROM subsector WHERE subsector_id = '40000000-0000-0000-0000-000000000018';

-- 2) Gayrimenkul — İnşaat sektörüyle örtüşen alt sektör
DELETE FROM subsector WHERE subsector_id = '40000000-0000-0000-0000-000000000044';

-- 3) Yeme & İçme — Fast Food/Restoran'ın kapsadığı aşırı bölünmüş alt kategoriler
DELETE FROM subsector WHERE subsector_id IN (
    '40000000-0000-0000-0000-000000000029', -- Hamburger
    '40000000-0000-0000-0000-000000000030', -- Pizza
    '40000000-0000-0000-0000-000000000035'  -- Steakhouse
);

-- 4) Teknoloji — B2B/yazılım odaklı alt sektörlerin tamamı
DELETE FROM subsector WHERE subsector_id IN (
    '40000000-0000-0000-0000-000000000074', -- SaaS
    '40000000-0000-0000-0000-000000000075', -- Yapay Zeka
    '40000000-0000-0000-0000-000000000076', -- Yazılım Firması
    '40000000-0000-0000-0000-000000000077', -- Mobil Uygulama
    '40000000-0000-0000-0000-000000000078', -- Siber Güvenlik
    '40000000-0000-0000-0000-000000000079'  -- Bulut Hizmetleri
);

-- Teknoloji (30000000-...-0012) altına tüketiciye yakın 4 yeni alt sektör
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date) VALUES
 ('40000000-0000-0000-0000-000000000201', '30000000-0000-0000-0000-000000000012', 'Teknoloji Mağazası', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000202', '30000000-0000-0000-0000-000000000012', 'Aksesuar Mağazası', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000203', '30000000-0000-0000-0000-000000000012', 'Teknik Servis', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000204', '30000000-0000-0000-0000-000000000012', 'Akıllı Ev Ürünleri', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
