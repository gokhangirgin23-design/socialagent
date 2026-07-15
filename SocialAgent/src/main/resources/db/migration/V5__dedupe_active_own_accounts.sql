-- D2 (tek hesap) kuralı: user_id+platform başına en fazla 1 aktif user_social_account satırı
-- olmalı, ama bunu zorlayan bir UNIQUE kısıt hiç olmadı (yalnızca UNIQUE(user_id, platform,
-- account_name) var). Yarış durumu veya eski test verisi nedeniyle birden fazla aktif satır
-- kalmış olabilir; bu durumda resolveOwnSocialAccountId/getOwnAccount gibi "ORDER BY olmadan
-- LIMIT 1" sorguları rastgele/eski hesabı seçip içerik üretimini yanlış hesaba göre kişiselleştirebilir
-- (bkz. AccountService.java, ContentRequestService.java, ReportRequestService.java — artık hepsi
-- ORDER BY updated_date DESC kullanıyor). Bu migration, her user_id+platform grubunda en güncel
-- satır dışındaki tüm aktif satırları bir kerelik pasife alır.
UPDATE user_social_account
SET active = 0, updated_date = now()
WHERE active = 1
  AND user_social_account_id NOT IN (
    SELECT keep.user_social_account_id
    FROM (
      SELECT user_social_account_id,
             ROW_NUMBER() OVER (
               PARTITION BY user_id, platform
               ORDER BY updated_date DESC, user_social_account_id DESC
             ) AS rn
      FROM user_social_account
      WHERE active = 1
    ) keep
    WHERE keep.rn = 1
  );
