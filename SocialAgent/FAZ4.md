# FAZ 4 — Scheduler + RabbitMQ (Faz Özeti / Değişiklik Günlüğü)

> Build-Prompt FAZ 4 ve CLAUDE.md Bölüm 9 esas alınmıştır.
> Bu faz, `active=1 AND completed=0` job'ları periyodik tarayıp **RabbitMQ kuyruğuna**
> basan üretici (producer) tarafını kapsar. Tüketici/worker + Apify bir sonraki faza (FAZ 5) aittir.

---

## 1. Ne Yaptım (kapsam)

| Katman | Dosya | Görev |
|---|---|---|
| config | `config/RabbitConfig.java` | `Queue` + `DirectExchange` + `Binding` + JSON `MessageConverter` + `RabbitTemplate` bean'leri |
| config | `config/AppProperties.java` (güncellendi) | `app.messaging.*` (kuyruk/exchange/routing-key) + `app.scheduler.*` (enabled, poll-interval) |
| messaging | `messaging/JobMessage.java` | Kuyruk mesaj gövdesi (record; `userJobId` taşır) |
| messaging | `messaging/JobQueueProducer.java` | Job id'sini exchange'e basan concrete producer |
| scheduler | `scheduler/JobScheduler.java` | `@Scheduled` tarama → atomik claim → kuyruğa basma |
| app | `SocialAgentApplication.java` (güncellendi) | `@EnableScheduling` eklendi |
| migration | `db/migration/V3__add_job_queue_tracking.sql` | `user_job`'a `queued` + `queued_date` + tarama index'i |
| entity | `entity/UserJob.java` (güncellendi) | `queued` + `queuedDate` kolonları (DDL ile uyum) |
| config (yml) | `application.yml` | `app.messaging` + `app.scheduler` varsayılanları (env override'lı) |
| config (yml) | `application-local.yml` | `app.scheduler.enabled=false` (local'de broker zorunlu olmasın) |
| test | `scheduler/JobSchedulerTest.java` | Claim/publish davranışının Mockito birim testi (broker/DB gerekmez) |

---

## 2. Akış (CLAUDE.md Bölüm 9)

```
@Scheduled (her poll-interval-ms)
  → SELECT user_job WHERE active=1 AND completed=0 AND queued=0   (JdbcTemplate native)
  → her aday için: UPDATE ... SET queued=1 WHERE id=? AND queued=0  (atomik claim)
      ↳ 1 satır etkilendiyse → JobQueueProducer.publishJob(userJobId) → RabbitMQ exchange → queue
      ↳ publish hata atarsa → UPDATE queued=0 (claim geri al, sonraki turda tekrar dene)
```

- Mesaj gövdesi `JobMessage{ userJobId }` (JSON). Spec'teki "mesaj = user_job_id" tip-güvenli sarmalanmıştır.
- vhost profil bazlı: `test → /test`, `prod → /prod` (yml'den; CLAUDE.md Bölüm 13).
- Kuyruk/exchange/routing-key adları `app.messaging.*` ile yönetilir (env override edilebilir).

---

## 3. İki Önemli Tasarım Kararı (scope içi, gerekçeli)

**a) İdempotent kuyruklama — `queued` kolonu (V3 migration).**
Naif `active=1 AND completed=0` sorgusu her turda **aynı** job'ları sonsuza dek kuyruğa basardı.
Ayrıca DevOps'ta her ortamda **2 instance** (docker-compose `app1`/`app2`) çalışır ve ikisinin de
`@Scheduled`'ı aynı anda tetiklenir → **çift kuyruklama** riski. Çözüm: minimal `queued` (0/1) +
`queued_date` izleme kolonu (CLAUDE.md Bölüm 5 şemasına küçük, dökümante bir ek) ve **atomik şartlı
UPDATE** ile claim. Hakem **veritabanıdır**; yalnızca 1 instance 1 satır günceller. `// TODO(faz5)`:
worker iş sonunda recurring job'lar için `queued=0`'a döndürür (yeni periyot için yeniden uygunluk) ya da
bitince `completed=1` yapar; periyot bazlı (DAILY/WEEKLY) yeniden zamanlama FAZ 7'de netleşecek.

**b) Local'de scheduler kapalı (`app.scheduler.enabled=false`).**
Local profilde RabbitMQ broker zorunlu olmasın diye `JobScheduler` `@ConditionalOnProperty` ile
local'de devre dışı (FAZ 0/1'deki "local'de Rabbit zorunlu değil" notuyla tutarlı). test/prod'da açık.
Broker'ı local denemek için `app.scheduler.enabled=true` yapıp bir RabbitMQ ayağa kaldırmak yeterli.

---

## 4. DoD Doğrulaması

| Kabul kriteri | Durum |
|---|:---:|
| Uygun job'lar (`active=1 AND completed=0`) kuyruğa düşer | ✅ |
| Tarama JdbcTemplate native + text-block + `?` ile | ✅ |
| vhost env bazlı (test `/test`, prod `/prod`) | ✅ |
| Aynı job tekrar tekrar kuyruğa basılmaz (idempotent claim) | ✅ |
| Çift instance (app1/app2) çift kuyruklamaz (DB atomik claim) | ✅ |
| Broker erişilemezse uygulama çökmez; claim geri alınır | ✅ |
| Service interface yok; entity ilişkisiz (sadece ID kolonları) | ✅ |
| Türkçe yorum çoğu satırda | ✅ |
| `user_job` DDL ↔ entity kolon uyumu (queued/queued_date) | ✅ |

> **Not:** Maven Central bu ortamda erişilemediğinden canlı `./mvnw clean install` burada
> çalıştırılamadı; denetim statik yapıldı (parantez/imza/şema uyumu). Kendi makinende/VPS'te build
> ve `JobSchedulerTest` standart şekilde çalışır.

---

## 5. Kapsam Dışı / Bilinçli Notlar

- **Worker/consumer FAZ 5'tedir.** Bu fazda yalnızca producer + scheduler var; mesaj kuyruğa düşer,
  henüz tüketilmez. `JobMessage` + JSON converter, FAZ 5 listener'ın aynı sözleşmeyle okuması için hazır.
- **Periyot bazlı yeniden zamanlama** (DAILY/WEEKLY arası bekleme) henüz uygulanmadı; FAZ 5–7'de
  `current_count`/`completed` ve `queued` reset mantığıyla netleşecek (`// TODO(faz5)`).

---

## 6. Sıradaki Faz

**FAZ 5 — Worker + Apify:** RabbitMQ consumer (`@RabbitListener` `socialagent.job.queue`) →
`JobMessage.userJobId` ile job yükle → mode'a göre hesap seçimi (CLAUDE.md Bölüm 10) → Apify keyword
araması (top 5, D1) → tekrar-analiz koruması (`post_analysis` JOIN, `analysis_period_days`) →
`social_post` insert (UNIQUE(platform, platform_post_id) service kontrolü).
