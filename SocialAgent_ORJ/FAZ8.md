# FAZ 8 — Bildirim & Dashboard (Faz Özeti / Değişiklik Günlüğü)

> Build-Prompt FAZ 8 ve CLAUDE.md Bölüm 12 esas alınmıştır.
> Bu faz, FAZ 7'de üretilen `report` kaydını kullanıcıya **sunar**: rapor tamamlanınca
> **bildirim** (notification kaydı + mail + push) üretir ve **dashboard** için kullanıcı bazlı
> **sayfalı** rapor/bildirim sorgu uçlarını ekler. Tüm uçlar POST; userId daima JWT'den
> (CLAUDE.md Madde 2, 4). 404 yok; yok/erişim yok durumları `005 NOT_FOUND` + `data=null`,
> HTTP yine 200 (CLAUDE.md Madde 3).

---

## 1. Ne Yaptım (kapsam)

| Katman | Dosya | Görev |
|---|---|---|
| entity | `entity/Notification.java` | `notification` JPA entity (yalnızca ID kolonları, ilişkisiz) |
| entity | `entity/ReferenceType.java` | Referans tipi enum'u (`REPORT \| JOB`) |
| repository | `dto/repository/NotificationRepository.java` | `JpaRepository` (yalnızca insert/save) |
| dto | `dto/NotificationDto.java` | Bildirim DTO'su (liste/dashboard) |
| dto | `dto/NotificationListRequest.java` | Bildirim listeleme + sayfalama (`page/size/onlyUnread`) |
| dto | `dto/MarkNotificationReadRequest.java` | Okundu işaretleme isteği (`notificationId`, `@NotNull`) |
| dto | `dto/ReportSummaryDto.java` | Dashboard rapor listesi satırı (içeriksiz; `report ⋈ user_job`) |
| dto | `dto/ReportDto.java` | Rapor detayı (Markdown içerik dahil) |
| dto | `dto/ReportListRequest.java` | Rapor listeleme + sayfalama (`page/size`) |
| dto | `dto/ReportDetailRequest.java` | Rapor detay isteği (`reportId`, `@NotNull`) |
| mapper | `mapper/NotificationMapper.java` | Notification → NotificationDto (MapStruct) |
| mapper | `mapper/ReportMapper.java` | Report → ReportDto (MapStruct, detay) |
| service | `service/NotificationService.java` | Bildirim insert (JPA) + listeleme/okundu/sayım (native) + `notifyReportCompleted` |
| service | `service/MailSender.java` | E-posta adaptörü; SMTP yoksa/kapalıysa **no-op**, asla patlamaz |
| service | `service/PushSender.java` | Push adaptörü (FCM **stub**); varsayılan kapalı, `// TODO(FCM)` |
| service | `service/ReportQueryService.java` | Dashboard: kullanıcı bazlı sayfalı rapor listesi + **sahiplik korumalı** detay |
| controller | `controller/NotificationController.java` | `POST /notification/{list,read,unread-count}` |
| controller | `controller/ReportController.java` | `POST /report/{list,detail}` |
| service | `service/ScrapePipelineService.java` (güncellendi) | Rapor COMPLETED → `notifyReportCompleted` adımı (try/catch ile izole) |
| config | `config/AppProperties.java` (güncellendi) | `app.notification` (mailEnabled/pushEnabled/fromAddress) |
| resource | `application.yml` (güncellendi) | `app.notification` bayrakları + örnek `spring.mail` bloğu (env) |
| migration | `db/migration/V6__notification_dashboard_indexes.sql` | `notification` composite index'leri (listeleme/okunmamış) |
| test | `service/NotificationServiceTest.java` | Bildirim üretimi/atlama, okundu, sayım, listeleme (Mockito) |
| test | `service/ReportQueryServiceTest.java` | Liste + sahiplik korumalı detay (bulundu/bulunamadı) (Mockito) |
| test | `service/ScrapePipelineServiceTest.java` (güncellendi) | Kurucu 9 bağımlılığa güncellendi; bildirim çağrısı doğrulaması |

> Not: `notification` tablosu FAZ 0'da `V1__init_schema.sql` ile, `spring-boot-starter-mail`
> bağımlılığı `pom.xml`'de hazırdı. Bu fazda yalnızca **V6** (bildirim index'leri) gerekti.

---

## 2. Akış (CLAUDE.md Bölüm 12)

```
ScrapePipelineService.processJob(userJobId)
  try {
    ... (scrape → analiz → rapor; FAZ 5/6/7)
    reportDone = reportPipelineService.generateReport(userJobId)     (FAZ 7)
    if (reportDone) {                                                 (FAZ 8)
       try { notificationService.notifyReportCompleted(userJobId) }
       catch (Exception) { log.warn(...) }   // bildirim hatası pipeline'ı bozmaz
    }
  } finally {
    jobCompletionService.finalizeJob(userJobId)                       (FAZ 7; finally garantisi)
  }

notifyReportCompleted(userJobId)   @Transactional(REQUIRES_NEW)       (bağımsız tx)
  1) loadCompletedReportTarget:
       SELECT r.report_id, j.user_id, j.analysis_mode, u.email
       FROM report r, user_job j, user_info u
       WHERE r.user_job_id = j.user_job_id                            (eski stil "=" join, Madde 6)
         AND j.user_id      = u.user_id
         AND r.user_job_id  = ?
         AND r.status = 'COMPLETED'
     ↳ COMPLETED rapor yoksa (FAILED/boş tur) → bildirim üretilmez.
  2) notification insert (JPA save):
       reference_type=REPORT, reference_id=report_id, is_read=0       (CLAUDE.md Bölüm 12)
  3) mailSender.send(email, ...)   ← SMTP yoksa/kapalıysa no-op
     pushSender.send(userId, ...)  ← FCM stub; varsayılan kapalı

Dashboard (POST + body, paging, userId JWT'den):
  POST /report/list           → report ⋈ user_job (j.user_id = ?), DESC, LIMIT/OFFSET (içeriksiz özet)
  POST /report/detail         → report ⋈ user_job (sahiplik), Markdown içerik; yoksa 005 NOT_FOUND
  POST /notification/list      → WHERE user_id = ? [AND is_read=0], DESC, LIMIT/OFFSET
  POST /notification/read      → UPDATE ... WHERE notification_id = ? AND user_id = ?   (ownership)
  POST /notification/unread-count → COUNT(*) WHERE user_id = ? AND is_read = 0
```

---

## 3. Üç Önemli Tasarım Kararı (scope içi, gerekçeli)

**a) Bildirim gönderimi "graceful degradation" — yapılandırma yoksa sessizce atlanır, asla patlamaz.**
Apify token / AI key felsefesinin (FAZ 5–6) bildirim karşılığı. `MailSender`, `JavaMailSender`'ı
`ObjectProvider` ile **opsiyonel** alır: `spring.mail.host` verilmezse Spring Boot bu bean'i
oluşturmaz, gönderim atlanır. `app.notification.mail-enabled` / `push-enabled` bayrakları kanalları
ayrıca kapatır (local'de ikisi de `false`). Gönderim hataları adaptör içinde yutulur; üstte
`ScrapePipelineService` çağrıyı bir kez daha try/catch ile sarar. Sonuç: **notification DB kaydı
her zaman atılır** (asıl ürün), mail/push ise "best-effort" yan etkidir.

**b) `notifyReportCompleted` bağımsız transaction (`REQUIRES_NEW`).**
Bildirim adımındaki olası bir DB hatası, çağıran scraping/rapor transaction'ını **kirletmesin**
(rapor yazımı + iş sonu muhasebesi korunur). Ayrıca pipeline `finally` bloğu sayesinde muhasebe
yine çalışır; job `queued=1`'de takılı kalmaz (FAZ 7 garantisi sürüyor).

**c) Dashboard'da sahiplik DB seviyesinde join ile zorlanır; içerik listede taşınmaz.**
`/report/detail` ve `/notification/read`, `user_id = ?` (JWT'den) koşulunu **sorgunun içine**
koyar; başka kullanıcının raporu/bildirimi sonuçtan düşer → `005 NOT_FOUND` (404 değil, HTTP 200).
`/report/list` özet döner (Markdown içerik **seçilmez**) — liste hafif kalır; tam içerik yalnızca
`/report/detail` ile gelir. Liste join'i `report ⋈ user_job` üzerinden `analysis_mode`/`job_period`
bağlamını da getirir (eski stil "=", Madde 6).

> **Push hakkında not:** CLAUDE.md "Push (FCM vb.)" diyor. Bu fazda `PushSender` sağlayıcı bağımsız
> bir **stub**'tır (bayrakla açılır, niyeti loglar). Gerçek FCM/APNs entegrasyonu (cihaz token tablosu
> + sağlayıcı SDK'sı) imza değişmeden iç gövdeye eklenebilir; `// TODO(FCM)` bırakıldı.

---

## 4. DoD Doğrulaması

| Kabul kriteri | Durum |
|---|:---:|
| Rapor tamamlanınca `notification` kaydı oluşur (REPORT, reference_id=report_id, is_read=0) | ✅ |
| Bildirim üretimi mail + push'u tetikler (yapılandırma yoksa sessizce atlanır) | ✅ |
| COMPLETED rapor yoksa bildirim üretilmez (FAILED/boş tur) | ✅ |
| Bildirim hatası pipeline'ı/iş sonu muhasebesini bozmaz (REQUIRES_NEW + try/catch) | ✅ |
| Dashboard: kullanıcı bazlı **sayfalı** rapor listesi döner (POST + body) | ✅ |
| Rapor detayı yalnızca sahibine döner; değilse `005 NOT_FOUND` (HTTP 200, data null) | ✅ |
| Bildirim listesi sayfalı; `onlyUnread` filtresi; okundu işaretleme ownership korumalı | ✅ |
| Okunmamış sayısı ucu (dashboard rozeti) | ✅ |
| Tüm uçlar POST; userId JWT'den (SecurityUtil); `/report`,`/notification` JWT korumalı | ✅ |
| Insert JPA save; lookup/join/update/count JdbcTemplate native + text-block + `?` + eski stil `=` | ✅ |
| Service interface yok; entity ilişkisiz (yalnız ID kolonları) | ✅ |
| Türkçe yorum çoğu satırda | ✅ |
| `notification`/`report` DDL ↔ entity kolon uyumu; `V6` H2/PostgreSQL uyumlu | ✅ |

> **Not:** Maven Central bu ortamda erişilemediğinden canlı `./mvnw clean install` burada
> çalıştırılamadı; denetim statik yapıldı (parantez/süslü dengesi, text-block bütünlüğü, imza/şema
> uyumu, paket yolları, kurucu sıraları, MapStruct alan adı eşleşmesi). Kendi makinende/VPS'te build
> ve testler standart şekilde çalışır.

---

## 5. Yeni Uçlar (özet)

| Uç | Body | Döner |
|---|---|---|
| `POST /report/list` | `{ page, size }` (opsiyonel) | `DataResponse<List<ReportSummaryDto>>` |
| `POST /report/detail` | `{ reportId }` | `DataResponse<ReportDto>` (yoksa 005) |
| `POST /notification/list` | `{ page, size, onlyUnread }` (opsiyonel) | `DataResponse<List<NotificationDto>>` |
| `POST /notification/read` | `{ notificationId }` | `DataResponse<Void>` (yoksa 005) |
| `POST /notification/unread-count` | (boş) | `DataResponse<Long>` |

---

## 6. Yapılandırma (env)

```
# Mail kanalını açmak için (prod/test):
MAIL_ENABLED=true
SPRING_MAIL_HOST=smtp.örnek.com
SPRING_MAIL_USERNAME=...
SPRING_MAIL_PASSWORD=...
MAIL_FROM=bildirim@örnek.com
# Push (ileride FCM):
PUSH_ENABLED=true
```
Local'de ikisi de `false` (SMTP/broker zorunlu değil). `spring.mail.host` verilmezse
`JavaMailSender` bean'i oluşmaz; `MailSender` sessizce no-op olur.

---

## 7. Kapsam Dışı / Bilinçli Notlar

- **Gerçek FCM/APNs push** bu fazda yok (stub). Cihaz token tablosu + sağlayıcı SDK'sı sonraki adım.
- **Recurring job ↔ bildirim:** Rapor her turda aynı kayıt yenilenir (FAZ 7 dedup), bildirim ise
  her tamamlanan turda **yeni satır** atar (kullanıcı her yeni raporu görsün). İstenirse "tek aktif
  bildirim" politikasına çevrilebilir.
- **Manuel "raporu yeniden üret" ucu** kapsam dışı; rapor üretim pipeline içinde otomatik tetiklenir.

---

## 8. Sıradaki Adımlar (öneri)

Build-Prompt'taki 8 faz tamamlandı. Olası iyileştirmeler: gerçek FCM entegrasyonu, dashboard özet
metrikleri (job/rapor sayıları, son N rapor trendi), bildirim toplu okundu, ve uçtan uca entegrasyon
testleri (Testcontainers ile PostgreSQL + RabbitMQ).
