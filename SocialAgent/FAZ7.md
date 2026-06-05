# FAZ 7 — Rapor + İş Sonu Muhasebesi (Faz Özeti / Değişiklik Günlüğü)

> Build-Prompt FAZ 7 ve CLAUDE.md Bölüm 11–12 ile FAZ 6.md §6 esas alınmıştır.
> Bu faz, FAZ 6'da `post_analysis.analysis_json`'a yazılan analizleri **tek bir Markdown rapora**
> dönüştüren katmanı ve bir scraping turunun ardından **iş sonu muhasebesini** (sayım, tamamlanma,
> kuyruk reset, periyot bazlı yeniden zamanlama) kapsar:
> bir job'ın tüm analizleri toplanır → OpenAI rapor prompt'u → **Markdown** → `report.report_content`;
> `report.status` akışı `PENDING → GENERATING → COMPLETED/FAILED` yönetilir. Bildirim ve dashboard
> sunumu bir sonraki faza (FAZ 8) aittir; `report` onun girdisidir.

---

## 1. Ne Yaptım (kapsam)

| Katman | Dosya | Görev |
|---|---|---|
| entity | `entity/ReportStatus.java` | Rapor durum enum'u (`PENDING/GENERATING/COMPLETED/FAILED`) |
| entity | `entity/Report.java` | `report` JPA entity (yalnızca ID kolonları, ilişkisiz) |
| repository | `dto/repository/ReportRepository.java` | `JpaRepository` (yalnızca insert/save) |
| dto | `dto/ReportPostRow.java` | Rapor girdisi için `record` projeksiyon (kaynak/medya/caption/hashtag/metrik/analizJson) |
| ai/prompt | `ai/prompt/ReportPrompts.java` | Tüm analizleri tek Türkçe **Markdown rapor** prompt'una çeviren template |
| ai | `ai/AiAnalysisService.java` (güncellendi) | `generateReport(String)` metodu + `cleanMarkdown` yardımcısı (OpenAI modeli yeniden kullanıldı) |
| service | `service/ReportService.java` | `report` insert (JPA) + durum geçişleri (native UPDATE) + dedup (`ensureReport`) |
| service | `service/ReportPipelineService.java` | Analizleri topla → prompt → rapor üret → durum yönet (orkestrasyon) |
| service | `service/JobCompletionService.java` | İş sonu muhasebesi: `current_count++`, `completed`, `queued` reset, `next_run_date` |
| service | `service/ScrapePipelineService.java` (güncellendi) | `processJob` try/finally'e taşındı; rapor + muhasebe adımları eklendi |
| scheduler | `scheduler/JobScheduler.java` (güncellendi) | Tarama sorgusuna `next_run_date` zamanlama filtresi eklendi |
| migration | `db/migration/V5__add_report_scheduling.sql` | `user_job.next_run_date` kolonu + index (periyot bazlı yeniden zamanlama) |
| test | `service/ReportServiceTest.java` | Durum geçişleri + dedup (`ensureReport`) davranışı (Mockito) |
| test | `service/ReportPipelineServiceTest.java` | Pipeline akışı + boş tur + FAILED yolu (Mockito) |
| test | `service/JobCompletionServiceTest.java` | Sayım/tamamlanma/yeniden zamanlama; UPDATE param doğrulama (ArgumentCaptor) |
| test | `service/ScrapePipelineServiceTest.java` (güncellendi) | Kurucu 8 bağımlılığa güncellendi; rapor + muhasebe çağrı doğrulaması eklendi |

> Not: `report` tablosu zaten FAZ 0'da `V1__init_schema.sql` ile oluşturulmuştu; bu fazda yalnızca
> **`user_job.next_run_date`** için yeni migration (`V5`) gerekti. `langchain4j-open-ai` bağımlılığı da
> `pom.xml`'de hazırdı (FAZ 0) — rapor üretimi mevcut OpenAI modelini yeniden kullanır.

---

## 2. Akış (CLAUDE.md Bölüm 11–12)

```
ScrapePipelineService.processJob(userJobId)
  try {
    1) scrape   (FAZ 5)  → social_post
    2) analyze  (FAZ 6)  → post_analysis.analysis_json
    3) report   (FAZ 7)  → ReportPipelineService.generateReport(userJobId)
         a) loadAnalyses: SELECT post_analysis, social_post
                          WHERE pa.social_post_id = sp.social_post_id   (eski stil "=" join, Madde 6)
                            AND sp.user_job_id = ?                       (JdbcTemplate native + text-block)
            ↳ kaynak etiketi social_post kolonlarından türetilir:
                monitored_account_id != null   → "RAKİP"
                account_name_sector dolu        → "SEKTÖR (ad)"
                aksi                            → "KENDİ HESABIN"
         b) ensureReport(userJobId)  → mevcut rapor varsa onu kullan, yoksa PENDING insert (JPA)  [dedup]
         c) markGenerating(reportId)                                      (native UPDATE: GENERATING)
         d) prompt = ReportPrompts.forJob(rows)
            markdown = AiAnalysisService.generateReport(prompt)
              ↳ OpenAI modeli (FAZ 6'daki model yeniden kullanılır)
              ↳ cleanMarkdown(): yalnız dış ``` blok çiti soyulur ('{}' korunur — JSON değil Markdown)
         e) markdown boş/null → markFailed(reportId)                      (native UPDATE: FAILED)
            aksi              → markCompleted(reportId, markdown)         (native UPDATE: COMPLETED + içerik)
  } finally {
    4) accounting (FAZ 7) → JobCompletionService.finalizeJob(userJobId)
         current_count++ ; completed? ; queued=0, queued_date=NULL ; next_run_date
  }
```

- **İş sonu muhasebesi (`finalizeJob`):** Tek native UPDATE ile yazılır:
  `current_count = current_count + 1`; `completed` kararı `decideCompleted(...)` ile
  (ON_DEMAND → 1; `repeat_count` dolu ve `current_count >= repeat_count` → 1; aksi → 0/devam);
  `queued = 0`, `queued_date = NULL` (scheduler yeniden alabilsin);
  devam eden recurring job için `next_run_date = now + periyot` (DAILY +1g / WEEKLY +1h / MONTHLY +1ay).
- **Periyot bazlı yeniden zamanlama:** `JobScheduler` tarama sorgusu artık
  `AND (next_run_date IS NULL OR next_run_date <= CURRENT_TIMESTAMP)` filtresini taşır;
  böylece zamanı gelmemiş recurring job'lar alınmaz. `CURRENT_TIMESTAMP` kullanıldı (bound param yok),
  H2 (MODE=PostgreSQL) ve PostgreSQL uyumlu.
- **finally garantisi:** Rapor/analiz/scrape adımlarından biri patlasa bile muhasebe **finally**'de çalışır;
  job `queued = 1` durumunda **takılı kalmaz** (redelivery/yeniden tetikleme güvenli).

---

## 3. İki Önemli Tasarım Kararı (scope içi, gerekçeli)

**a) Rapor üretimi yeni model kurmaz; mevcut `AiAnalysisService` (OpenAI) yeniden kullanılır.**
FAZ 6'da modellerin yaşam döngüsü tek sınıfta (`@PostConstruct`, key yoksa `null`) toplanmış ve
"LangChain4j sürüm farkları yalnızca burada düzeltilsin" gerekçesiyle kilitlenmişti. Rapor için ayrı bir
model sınıfı açmak bu kararı bozar ve key/guard mantığını ikinci bir yere kopyalardı. Bunun yerine
`AiAnalysisService`'e `generateReport(String prompt)` eklendi: aynı OpenAI modeli, aynı key-guard, aynı
güvenli devre dışı kalma (key boşsa rapor üretilmez, `markFailed`, uygulama çökmez). Tek fark çıktı
biçimidir: analiz JSON üretirken rapor **Markdown** üretir; bu yüzden `cleanJson` yerine yeni `cleanMarkdown`
kullanıldı — yalnız dış ``` blok çitini soyar, gövdedeki `{}`/`#`/tabloları **korur**.

**b) Job başına TEK rapor (recurring'de aynı kayıt yenilenir) — `ensureReport` ile dedup.**
`report` ile `user_job` arasında 1–1 ilişki benimsendi. Recurring bir job her tamamlandığında **yeni satır
açmak yerine** mevcut raporu `GENERATING → COMPLETED` ile günceller. Gerekçe: dashboard (FAZ 8) "job başına
güncel rapor" gösterir; her turda yeni satır birikmesi hem şişme hem "hangi rapor güncel?" karmaşası yaratırdı.
`ensureReport(userJobId)` önce `findReportIdByJob` ile bakar (native lookup), yoksa `createPending` (JPA save).
Bu, FAZ 6'daki `PostAnalysisService` dedup felsefesinin rapor tarafındaki karşılığıdır; pipeline yeniden
çalışsa da tek rapor korunur (idempotent).

> **Kaynak etiketi join'i hakkında not:** `loadAnalyses`, `monitored_account` tablosuna **join atmaz**.
> Eski stil inner `=` join, `monitored_account_id` NULL olan (KENDİ/SEKTÖR) postları düşürürdü. Etiket
> (`RAKİP` / `SEKTÖR (ad)` / `KENDİ HESABIN`) doğrudan `social_post` kolonlarından türetilir; böylece hem
> Madde 6 (inner join `=`) ihlal edilmez hem de nullable FK'li postlar rapora dahil olur.

---

## 4. DoD Doğrulaması

| Kabul kriteri | Durum |
|---|:---:|
| Bir job'ın tüm `post_analysis` JSON'ları toplanır (analiz ⋈ post, eski stil `=`) | ✅ |
| Toplanan analizler OpenAI ile **tek Markdown rapora** dönüştürülür | ✅ |
| Rapor `report.report_content`'e yazılır; durum `PENDING → GENERATING → COMPLETED` | ✅ |
| Rapor üretimi başarısız/boşsa durum `FAILED` olur (uygulama çökmez) | ✅ |
| OpenAI key boşsa rapor atlanır → `FAILED`; pipeline ve muhasebe devam eder | ✅ |
| Job başına tek rapor (recurring'de aynı kayıt yenilenir, `ensureReport` dedup) | ✅ |
| Model çıktısı dış ``` blok çitinden arındırılır, Markdown gövde korunur (`cleanMarkdown`) | ✅ |
| İş sonu: `current_count++` her tamamlanan turda uygulanır | ✅ |
| ON_DEMAND → `completed=1`; `repeat_count` dolduğunda → `completed=1` | ✅ |
| Devam eden recurring job: `queued` reset + `next_run_date = now + periyot` | ✅ |
| Scheduler zamanı gelmemiş job'ı almaz (`next_run_date` filtresi) | ✅ |
| Muhasebe `finally`'de — hata olsa da job `queued=1`'de takılı kalmaz | ✅ |
| Lookup/UPDATE JdbcTemplate native + text-block + `?`; insert JPA save | ✅ |
| Service interface yok; entity ilişkisiz (yalnızca ID kolonları) | ✅ |
| Türkçe yorum çoğu satırda | ✅ |
| `report` DDL ↔ entity kolon uyumu; `V5` migration H2/PostgreSQL uyumlu | ✅ |

> **Not:** Maven Central bu ortamda erişilemediğinden canlı `./mvnw clean install` burada çalıştırılamadı;
> denetim statik yapıldı (parantez/süslü dengesi, imza/şema uyumu, paket yolları, kurucu sıraları). Kendi
> makinende/VPS'te build ve testler standart şekilde çalışır.
> **LangChain4j uyum notu:** `generateReport`, FAZ 6'daki OpenAI `ChatModel.chat(...)` imzasını birebir
> kullanır; sürüm farkı çıkarsa yine yalnızca `AiAnalysisService` (ve gerekirse testi) etkilenir.

---

## 5. Kapsam Dışı / Bilinçli Notlar

- **Bildirim ve dashboard FAZ 8'dedir.** Bu fazda rapor üretilip `report`'a yazılır; kullanıcıya bildirim
  (push/e-posta) ve raporların listelenmesi/paging (job başına 1 rapor) FAZ 8 kapsamındadır.
- **Rapor endpoint'i bu fazda yok.** Üretim pipeline (scrape→analiz→rapor→muhasebe) içinde otomatik tetiklenir;
  manuel "raporu getir/üret" POST endpoint'i dashboard ile birlikte FAZ 8'de eklenir.
- **`next_run_date` saat hassasiyeti basittir.** Periyot eklemesi `now + 1 gün/hafta/ay` olarak hesaplanır;
  sabit çalışma saati/zaman dilimi politikaları (örn. "her gün 09:00") ileride genişletilebilir (`// TODO`).
- **Rapor prompt'u toleranslı okur.** Analiz JSON'ları katı şema doğrulamasından geçmediği için (FAZ 6 notu),
  `ReportPrompts.forJob` ham analiz metnini esnek biçimde özetler; caption'lar `CAPTION_MAX=280` ile kırpılır.

---

## 6. Sıradaki Faz

**FAZ 8 — Bildirim & Dashboard:** Tamamlanan `report` kayıtları kullanıcıya sunulur — rapor listeleme/
detay POST endpoint'leri (job başına güncel rapor), durum bilgisiyle birlikte. Rapor tamamlandığında
kullanıcıya bildirim (push/e-posta) gönderimi ve dashboard özet metrikleri bu fazda netleşir.
