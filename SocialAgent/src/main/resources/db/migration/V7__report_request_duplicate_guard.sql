-- E7 bug fix: "Oluştur" butonuna hızlı çift tık (~3ms arayla) 2 ayrı report_request
-- oluşturup ikisi de tamamlanınca kredi 2 kere düşüyordu. Servis katmanındaki SELECT-then-INSERT
-- ön kontrolü tek başına bu yarış durumunu kapatamaz (iki eşzamanlı istek ön kontrolü aynı anda
-- geçebilir, üstelik prod'da 2 app instance'ı var — JVM içi senkronizasyon da yetmez).
-- Bu yüzden gerçek atomiklik DB seviyesinde sağlanır: active_lock_key yalnızca istek PENDING/
-- PROCESSING iken user_id değerini taşır, terminal duruma geçince NULL'a döner (bkz.
-- ScrapePipelineService.markFinished). Standart SQL'de NULL değerler UNIQUE kısıtından muaftır,
-- bu yüzden bu kolon üzerindeki düz UNIQUE constraint "kullanıcı başına en fazla 1 aktif rapor
-- isteği" kuralını hem H2 hem PostgreSQL'de aynı şekilde ve portatif biçimde uygular.

ALTER TABLE report_request ADD COLUMN active_lock_key UUID;
ALTER TABLE report_request ADD CONSTRAINT uq_report_request_active_lock UNIQUE (active_lock_key);
