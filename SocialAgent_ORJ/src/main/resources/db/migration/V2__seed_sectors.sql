-- ============================================================
-- SocialAgent - V2 Sektör ve alt sektör tohum verisi
-- H2 (MODE=PostgreSQL) ve PostgreSQL ile uyumludur.
-- UUID'ler sabit; her ortamda aynı veri oluşur.
-- Tüm kayıtlar active=1, created_date/updated_date şimdiki zaman.
-- ============================================================

-- ============================================================
-- SEKTÖRLER (6 adet)
-- ============================================================

-- Teknoloji sektörü
INSERT INTO sector (sector_id, name, active, created_date, updated_date)
VALUES ('11111111-1111-1111-1111-111111111101', 'Teknoloji', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Moda sektörü
INSERT INTO sector (sector_id, name, active, created_date, updated_date)
VALUES ('11111111-1111-1111-1111-111111111102', 'Moda', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Yeme-İçme sektörü
INSERT INTO sector (sector_id, name, active, created_date, updated_date)
VALUES ('11111111-1111-1111-1111-111111111103', 'Yeme-İçme', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Sağlık sektörü
INSERT INTO sector (sector_id, name, active, created_date, updated_date)
VALUES ('11111111-1111-1111-1111-111111111104', 'Sağlık', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Eğitim sektörü
INSERT INTO sector (sector_id, name, active, created_date, updated_date)
VALUES ('11111111-1111-1111-1111-111111111105', 'Eğitim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Spor sektörü
INSERT INTO sector (sector_id, name, active, created_date, updated_date)
VALUES ('11111111-1111-1111-1111-111111111106', 'Spor', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ============================================================
-- ALT SEKTÖRLER
-- Her sektör için 3-4 alt sektör
-- ============================================================

-- ---- Teknoloji alt sektörleri ----
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222101', '11111111-1111-1111-1111-111111111101', 'Mobil Uygulama', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222102', '11111111-1111-1111-1111-111111111101', 'Yapay Zeka', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222103', '11111111-1111-1111-1111-111111111101', 'Siber Güvenlik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222104', '11111111-1111-1111-1111-111111111101', 'Yazılım Geliştirme', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ---- Moda alt sektörleri ----
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222201', '11111111-1111-1111-1111-111111111102', 'Giyim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222202', '11111111-1111-1111-1111-111111111102', 'Aksesuar', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222203', '11111111-1111-1111-1111-111111111102', 'Güzellik ve Kozmetik', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222204', '11111111-1111-1111-1111-111111111102', 'Lüks Moda', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ---- Yeme-İçme alt sektörleri ----
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222301', '11111111-1111-1111-1111-111111111103', 'Restoran', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222302', '11111111-1111-1111-1111-111111111103', 'Kafe ve Kahve', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222303', '11111111-1111-1111-1111-111111111103', 'Fast Food', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222304', '11111111-1111-1111-1111-111111111103', 'Fırın ve Pastane', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ---- Sağlık alt sektörleri ----
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222401', '11111111-1111-1111-1111-111111111104', 'Klinik ve Hastane', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222402', '11111111-1111-1111-1111-111111111104', 'Fitness ve Wellness', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222403', '11111111-1111-1111-1111-111111111104', 'Beslenme ve Diyet', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ---- Eğitim alt sektörleri ----
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222501', '11111111-1111-1111-1111-111111111105', 'Online Eğitim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222502', '11111111-1111-1111-1111-111111111105', 'Dil Öğrenimi', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222503', '11111111-1111-1111-1111-111111111105', 'Kurs ve Sertifika', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222504', '11111111-1111-1111-1111-111111111105', 'K-12 Eğitim', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ---- Spor alt sektörleri ----
INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222601', '11111111-1111-1111-1111-111111111106', 'Spor Kulübü', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222602', '11111111-1111-1111-1111-111111111106', 'Spor Malzemeleri', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO subsector (subsector_id, sector_id, name, active, created_date, updated_date)
VALUES ('22222222-2222-2222-2222-222222222603', '11111111-1111-1111-1111-111111111106', 'E-Spor', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
