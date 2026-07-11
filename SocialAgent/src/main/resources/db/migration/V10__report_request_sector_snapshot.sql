-- ============================================================
-- V10 — Rapor listesinde sektör/alt sektör/hesap bilgisi gösterimi (Gökhan isteği, 2026-07-12)
-- ============================================================
-- report_request'e sektör/alt sektör ANLIK OLARAK donduruluyor (tıpkı selected_user_social_account_id
-- gibi) — kullanıcı sektörünü sonradan değiştirirse eski raporların sektör bilgisi yanlış
-- görünmesin diye (canlı user_info'ya join yapmak yerine oluşturma anındaki değer saklanır).
-- Nullable: bu migration'dan ÖNCE oluşturulmuş raporlarda bu alanlar boş kalır (UI'da "—" gösterilir).
-- ============================================================

ALTER TABLE report_request ADD COLUMN sector_id UUID;
ALTER TABLE report_request ADD COLUMN subsector_id UUID;
