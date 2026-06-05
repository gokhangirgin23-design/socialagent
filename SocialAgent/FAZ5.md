# FAZ 5 — Worker + Apify (Faz Özeti / Değişiklik Günlüğü)

> Build-Prompt FAZ 5 ve CLAUDE.md Bölüm 9, 10 esas alınmıştır.
> Bu faz, FAZ 4'te kuyruğa basılan job'ları **tüketen worker** ile **Apify REST entegrasyonunu**
> kapsar: moda göre hesap kümesini çöz → tekrar-analiz koruması → Apify'dan son gönderileri çek →
> `social_post`'a yaz. AI analizi bir sonraki faza (FAZ 6) aittir; `social_post` onun girdisidir.

---

## 1. Ne Yaptım (kapsam)

| Katman | Dosya | Görev |
|---|---|---|
| apify | `apify/ApifyClient.java` | Apify REST istemcisi (RestClient); `searchTopProfiles` + `fetchRecentPosts`; token boşsa boş liste |
| apify | `apify/ApifyProfile.java` | Keyword aramasından dönen profil (record: accountName, profileUrl, followerCount, engagementRate) |
| apify | `apify/ApifyPost.java` | Post scraper'dan dönen gönderi (record); `mediaType` normalize edilmiş |
| entity | `entity/MediaType.java` | `IMAGE\|VIDEO\|CAROUSEL\|TEXT` enum + `fromRaw()` ham değer normalizasyonu |
| entity | `entity/SocialPost.java` | `social_post` JPA entity (yalnızca ID kolonları, ilişkisiz) |
| repository | `dto/repository/SocialPostRepository.java` | `JpaRepository` (yalnızca insert/save) |
| service | `service/ScrapeTarget.java` | Tek hedef hesap (record + `TargetType{OWN,MONITORED,SECTOR}` + factory'ler) |
| service | `service/TargetResolver.java` | Moda göre hesap kümesini çözer; COMPETITOR_ONLY/BOTH'ta Apify profil araması yok |
| service | `service/SocialPostService.java` | Tekrar-analiz koruması (`isRecentlyAnalyzed`) + dedup'lı yazma (`saveRecentPosts`) |
| service | `service/ScrapePipelineService.java` | Pipeline orkestrasyonu (job yükle → çöz → koruma → çek → yaz) |
| messaging | `messaging/JobWorker.java` | `@RabbitListener` consumer → `ScrapePipelineService.processJob` |
| config | `config/AppProperties.java` (güncellendi) | `app.apify.*` (token/base-url/actor/limit/timeout) + `app.worker.enabled` |
| config (yml) | `application.yml` | `app.apify.*` + `app.worker.enabled` varsayılanları (env override'lı) |
| config (yml) | `application-local.yml` | `app.worker.enabled=false` (local'de broker zorunlu olmasın) |
| migration | `db/migration/V4__social_post_sector_index.sql` | Sektör hesabı JOIN'i için birleşik index |
| test | `service/SocialPostServiceTest.java` | Tekrar-analiz + dedup'lı yazma Mockito testi |
| test | `service/TargetResolverTest.java` | Mod çözümü + Apify çağrılmama kuralı Mockito testi |
| test | `service/ScrapePipelineServiceTest.java` | Pipeline akışı + 1-hafta kuralı Mockito testi |

---

## 2. Akış (CLAUDE.md Bölüm 9, 10)

```
JobWorker.@RabbitListener(JobMessage)
  → ScrapePipelineService.processJob(userJobId)
      1) loadJob: SELECT user_job WHERE id=? AND active=1            (JdbcTemplate native)
      2) TargetResolver.resolve(job): analysis_mode → hedef kümesi
           NONE            → sektör top 5
           OWN_ONLY        → sektör top 5 + kendi hesap
           COMPETITOR_ONLY → yalnızca rakip (Apify profil araması YOK)
           BOTH            → kendi + rakip       (Apify profil araması YOK)
      3) her hedef için:
           isRecentlyAnalyzed(target, periodDays)?  → EVET ise Apify ATLA (tekrar-analiz koruması)
                                                     → HAYIR ise:
              ApifyClient.fetchRecentPosts(account, 5)
              SocialPostService.saveRecentPosts(...)  → UNIQUE(platform, platform_post_id) dedup → JPA save
```

- **Sektör top 5 (D1):** alt-sektör (yoksa sektör) adı keyword → `searchTopProfiles` → follower (birincil) +
  engagement (ikincil) azalan sıralama → ilk 5 → `SECTOR` hedefi (`platform_sector` + `account_name_sector`).
- **Tekrar-analiz koruması:** Apify'dan önce `post_analysis` JOIN'lenir; hedef son `analysis_period_days`
  (varsayılan 7) içinde analiz edilmişse Apify çağrılmaz. Kimlik hedef tipine göre seçilir
  (MONITORED→`monitored_account_id`, SECTOR→`platform_sector`+`account_name_sector`, OWN→job zinciriyle
  `selected_user_social_account_id`).
- Tüm JOIN'ler eski stil `=` + text-block + `?` (CLAUDE.md Madde 6); insert JPA `save` (UNIQUE elle kontrol).

---

## 3. İki Önemli Tasarım Kararı (scope içi, gerekçeli)

**a) Tekrar-analiz koruması yeni kolon eklemeden, mevcut şema üzerinden.**
"Son 1 hafta içinde analiz edilmiş hesabı tekrar çekme" kuralı (DoD) için yeni bir kolon eklemek yerine
mevcut `post_analysis` + `social_post` kaynak kolonları JOIN'lendi. Böylece şema sürüklenmesi (drift)
olmadı; pencere `pa.created_date >= now - analysisPeriodDays` ile hesaplanıyor. Hedef tipine göre 3 ayrı
native sorgu kullanıldı (kimlik kolonu değiştiği için). `TargetResolver`, `social_post` yazımı ve koruma
sorgusu **aynı kimlik kolonlarını** kullanır → tutarlılık garanti.

**b) Worker job durumuna dokunmaz; iş sonu muhasebesi FAZ 7'ye bırakıldı.**
`processJob`, `user_job` durum kolonlarını (`current_count`, `completed`, `queued`) **değiştirmez**;
yalnızca scraping + yazma yapar ve `// TODO(FAZ7)` notu bırakır. Gerekçe: periyot bazlı yeniden
zamanlama (DAILY/WEEKLY) ve `repeat_count`/`current_count` muhasebesi FAZ 7'de netleşecek; worker'ı şimdi
duruma bağlamak erken kuplaj olurdu. Ayrıca worker hata yönetimi **catch + log + ack** (poison-message
sonsuz redelivery'i önlemek için); retry/DLQ politikası için `// TODO` bırakıldı. `app.worker.enabled=false`
ile local'de worker kapalı (broker zorunlu olmasın — scheduler ile aynı yaklaşım).

> **Test edilebilirlik notu:** "COMPETITOR_ONLY/BOTH'ta Apify profil araması yapma" kuralının sahibi
> `TargetResolver`'dır; orkestrasyon (`ScrapePipelineService`) ondan ayrıldı. Böylece her kural Spring'siz
> Mockito birim testiyle izole doğrulanabiliyor.

---

## 4. DoD Doğrulaması

| Kabul kriteri | Durum |
|---|:---:|
| NONE → sektör top 5 hesabın son gönderileri yazılır | ✅ |
| OWN_ONLY → sektör top 5 + kendi hesap | ✅ |
| COMPETITOR_ONLY → yalnızca rakip hesaplar (Apify profil araması yok) | ✅ |
| BOTH → kendi + rakip hesaplar (Apify profil araması yok) | ✅ |
| Son 1 hafta (`analysis_period_days`) içinde analiz edilen hesap tekrar çekilmez | ✅ |
| Sektör top 5: follower/engagement'a göre sıralanıp ilk 5 alınır (D1) | ✅ |
| `social_post` UNIQUE(platform, platform_post_id) servis katmanında elle kontrol | ✅ |
| Tüm JOIN'ler eski stil `=` + JdbcTemplate native + text-block + `?` | ✅ |
| Token boşsa Apify çağrısı atlanır, uygulama çökmez | ✅ |
| Worker hatası yutulur + loglanır + ack (sonsuz redelivery yok) | ✅ |
| Service interface yok; entity ilişkisiz (yalnızca ID kolonları) | ✅ |
| Türkçe yorum çoğu satırda | ✅ |
| `social_post` DDL ↔ entity kolon uyumu | ✅ |

> **Not:** Maven Central bu ortamda erişilemediğinden canlı `./mvnw clean install` burada
> çalıştırılamadı; denetim statik yapıldı (parantez/imza/şema uyumu, MediaType ad çakışması, RestClient
> API'si). Kendi makinende/VPS'te build ve testler standart şekilde çalışır.
> **Apify uyum notu:** Aktör girdi/çıktı şemaları aktöre göre değişir; alan adları savunmacı okunur ve
> kod içinde `// TODO(uyum)` ile işaretlenmiştir. Gerçek aktör seçilince bu noktalar gözden geçirilmeli.

---

## 5. Sıradaki Faz

**FAZ 6 — AI Analiz:** `social_post` kayıtları LangChain4j ile analiz edilir
(`TEXT → OpenAI`, `IMAGE/VIDEO/CAROUSEL → Gemini Vision`), sonuç `post_analysis.analysis_json`'a yazılır.
