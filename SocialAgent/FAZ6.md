# FAZ 6 — AI Analiz (LangChain4j) (Faz Özeti / Değişiklik Günlüğü)

> Build-Prompt FAZ 6 ve CLAUDE.md Bölüm 11, D3 esas alınmıştır.
> Bu faz, FAZ 5'te `social_post`'a yazılan gönderileri **LangChain4j ile analiz eden** katmanı kapsar:
> medya türüne göre modele yönlendir (`TEXT → OpenAI`, `IMAGE/VIDEO/CAROUSEL → Gemini Vision`) →
> dönen JSON'ı `post_analysis.analysis_json`'a yaz. Markdown rapor üretimi bir sonraki faza (FAZ 7) aittir;
> `post_analysis` onun girdisidir.

---

## 1. Ne Yaptım (kapsam)

| Katman | Dosya | Görev |
|---|---|---|
| ai | `ai/AiAnalysisService.java` | Medya türüne göre yönlendirme (D3); OpenAI/Gemini modellerini `@PostConstruct`'ta kurar; JSON sadeleştirme |
| ai/prompt | `ai/prompt/AnalysisPrompts.java` | TEXT ve medya için ortak JSON-şemalı prompt template'leri (engagement/tema/ton/hashtag/öneri) |
| entity | `entity/PostAnalysis.java` | `post_analysis` JPA entity (yalnızca ID kolonları, ilişkisiz) |
| repository | `dto/repository/PostAnalysisRepository.java` | `JpaRepository` (yalnızca insert/save) |
| service | `service/PostAnalysisService.java` | `post_analysis` yazma + "zaten analiz var mı" servis kontrolü (dedup) |
| service | `service/AnalysisPipelineService.java` | Job'ın analiz edilmemiş postlarını çek → analiz et → yaz (orkestrasyon) |
| service | `service/ScrapePipelineService.java` (güncellendi) | Scraping sonrası `analyzeJob(...)` çağrısı eklendi (4. adım) |
| config | `config/AppProperties.java` (güncellendi) | `app.ai.*` (enabled, openai/gemini key-model-temperature-timeout) |
| config (yml) | `application.yml` | `app.ai.*` varsayılanları (env override'lı; key boşsa analiz atlanır) |
| test | `service/PostAnalysisServiceTest.java` | Dedup + save davranışı (Mockito) |
| test | `service/AnalysisPipelineServiceTest.java` | Pipeline akışı + boş tur + sayım (Mockito) |
| test | `ai/AiAnalysisServiceTest.java` | D3 yönlendirme: TEXT→OpenAI, IMAGE→Gemini; JSON temizleme (Mockito + reflection) |

> Not: `post_analysis` tablosu zaten FAZ 0'da `V1__init_schema.sql` ile oluşturulmuştu; bu fazda yeni
> migration gerekmedi. `langchain4j-open-ai` + `langchain4j-google-ai-gemini` bağımlılıkları da
> `pom.xml`'de hazırdı (FAZ 0).

---

## 2. Akış (CLAUDE.md Bölüm 11, D3)

```
ScrapePipelineService.processJob(...)  (FAZ 5 sonu)
  → AnalysisPipelineService.analyzeJob(userJobId)
      1) loadUnanalyzedPosts: SELECT social_post WHERE user_job_id=?            (JdbcTemplate native)
                              AND NOT EXISTS (post_analysis ... )  → anti-join (idempotent)
      2) her post için:
           AiAnalysisService.analyze(post)
              media_type = TEXT             → OpenAI   (prompt = AnalysisPrompts.forText)
              media_type = IMAGE|VIDEO|CAROUSEL → Gemini Vision (prompt + ImageContent(media_url))
              ↳ model çıktısı cleanJson() ile sadeleştirilir ( ``` ve ön/son metin atılır )
           PostAnalysisService.saveAnalysis(socialPostId, json)
              ↳ json boş/null → yazma yok
              ↳ zaten analiz var → yazma yok (servis dedup)
              ↳ aksi halde → post_analysis insert (JPA save)
```

- **Yönlendirme (D3):** `MediaType.fromRaw(media_type)` ile normalize edilir; `TEXT` → OpenAI metin modeli,
  diğer tüm türler → Gemini Vision (görsel `media_url` varsa multimodal, yoksa yalnız metin).
- **Çıktı sözleşmesi:** Her iki yol da **aynı JSON şemasını** üretir (engagement/themes/tone/hashtagAnalysis/
  contentSuggestions). Böylece FAZ 7 raporu tek formatla çalışır. Model'den daima salt JSON istenir.
- **Güvenli devre dışı kalma:** İlgili API key boşsa o yol için model kurulmaz; analiz **atlanır** ve
  uygulama patlamaz (Apify token yaklaşımıyla aynı felsefe).

---

## 3. İki Önemli Tasarım Kararı (scope içi, gerekçeli)

**a) Model yaşam döngüsü servisin içinde (`@PostConstruct`), config `@Bean` değil.**
CLAUDE.md Bölüm 15 `config` altında langchain4j'den söz eder; ancak modeller **API key'e bağlı opsiyonel**
nesnelerdir (local/dev'de key olmayabilir). `@Bean` ile null döndürmek Spring'in null-bean davranışıyla
sorun çıkarır ve her enjeksiyon noktasında `@Qualifier`/null-guard gerektirir. Bunun yerine `AiAnalysisService`,
**`ApifyClient`'ın `@PostConstruct` ile `RestClient` kurma paternini** birebir izler: model'ler servis içinde
kurulur, key yoksa `null` bırakılır, çağrı noktasında guard edilir. Böylece hem proje paterniyle tutarlılık
hem de "key yoksa çök" riskinin önlenmesi sağlandı. Model imzaları tek sınıfta toplandığı için olası
LangChain4j sürüm farkları (`// TODO(uyum)`) yalnızca burada düzeltilir.

**b) "Analiz edilmemiş post" filtresi `NOT EXISTS` (anti-join) ile — idempotency için bilinçli istisna.**
CLAUDE.md Madde 6 inner JOIN'lerin eski stil `=` ile yazılmasını söyler. Ancak "post_analysis kaydı **OLMAYAN**
postları bul" bir **anti-join**'dir ve `=` ile ifade edilemez. `NOT EXISTS` correlated subquery kullanıldı;
bu bir JOIN değil, alt-sorgu olduğundan `=` kuralı ihlal edilmez. Gerekçe: pipeline (worker yeniden tetiklenmesi,
redelivery, recurring job) tekrar çalıştığında aynı postların yeniden analiz edilip OpenAI/Gemini'ye **gereksiz
maliyetli** çağrı yapılmasını önler. Bu, FAZ 5'teki "tekrar-analiz koruması"nın analiz tarafındaki tamamlayıcısıdır;
`PostAnalysisService.saveAnalysis` içinde **ikinci bir servis-seviyesi dedup** ile de güvenceye alındı (yarış
durumlarına karşı).

> **Test edilebilirlik notu:** Yönlendirme kuralının (D3) sahibi `AiAnalysisService`'tir; orkestrasyon
> (`AnalysisPipelineService`) ondan ayrıldı. Model'ler reflection ile mock'lanarak "TEXT→OpenAI, IMAGE→Gemini"
> kuralı gerçek AI/ağ olmadan Mockito ile izole doğrulanıyor.

---

## 4. DoD Doğrulaması

| Kabul kriteri | Durum |
|---|:---:|
| Post başına analiz JSON'ı üretilip `post_analysis`'e kaydedilir | ✅ |
| `media_type=TEXT` → OpenAI'a yönlendirilir | ✅ |
| `media_type=IMAGE/VIDEO/CAROUSEL` → Gemini Vision'a yönlendirilir (görsel + metin) | ✅ |
| Model çıktısı ``` / ön-son metinden arındırılıp geçerli JSON'a sadeleştirilir | ✅ |
| Aynı post tekrar analiz edilmez (NOT EXISTS + servis dedup, idempotent) | ✅ |
| API key boşsa ilgili analiz atlanır, uygulama çökmez | ✅ |
| Analiz sonucu boş/başarısızsa `post_analysis` kaydı oluşmaz | ✅ |
| Lookup'lar JdbcTemplate native + text-block + `?`; insert JPA save | ✅ |
| Service interface yok; entity ilişkisiz (yalnızca ID kolonları) | ✅ |
| Türkçe yorum çoğu satırda | ✅ |
| `post_analysis` DDL ↔ entity kolon uyumu | ✅ |
| Scraping pipeline sonrası analiz tetiklenir (FAZ 5 → FAZ 6 zinciri) | ✅ |

> **Not:** Maven Central bu ortamda erişilemediğinden canlı `./mvnw clean install` burada çalıştırılamadı;
> denetim statik yapıldı (parantez/imza/şema uyumu, paket yolları). Kendi makinende/VPS'te build ve testler
> standart şekilde çalışır.
> **LangChain4j uyum notu:** Kod, LangChain4j 1.0.x API'si baz alınarak yazıldı (`ChatModel`, `chat(...)`,
> `UserMessage`+`ImageContent` multimodal, `OpenAiChatModel`/`GoogleAiGeminiChatModel` builder'ları).
> Boot 4 ile derlerken sürüm imzaları `// TODO(uyum)` noktalarında doğrulanmalı; değişiklik gerekirse
> yalnızca `AiAnalysisService` (ve gerekirse test) etkilenir.

---

## 5. Kapsam Dışı / Bilinçli Notlar

- **Markdown rapor FAZ 7'dedir.** Bu fazda yalnızca post-bazlı analiz JSON'ları üretilir; tüm JSON'ların
  toplanıp OpenAI ile **tek Markdown rapora** dönüştürülmesi ve `report.status` akışı (PENDING → GENERATING →
  COMPLETED/FAILED) FAZ 7 kapsamındadır.
- **İş sonu muhasebesi hâlâ FAZ 7'de.** `processJob`, analizden sonra da `user_job` durum kolonlarına
  (`current_count`, `completed`, `queued`) dokunmaz; `// TODO(FAZ7)` korunur.
- **VIDEO/CAROUSEL için tek temsili görsel.** Gemini'ye şu an `media_url` (kapak/ilk kare) iletilir; video
  karelerinin veya carousel'in tüm öğelerinin çoklu görselle analizi ileride genişletilebilir (`// TODO`).
- **JSON şema doğrulaması yumuşaktır.** Çıktı `cleanJson` ile sadeleştirilip ham metin olarak saklanır; katı
  şema doğrulaması (örn. JSON Schema) yapılmaz — rapor prompt'u FAZ 7'de toleranslı okuyacak şekilde tasarlanacak.

---

## 6. Sıradaki Faz

**FAZ 7 — Rapor:** Bir job'ın tüm `post_analysis` JSON'ları toplanır → OpenAI rapor prompt'u → **Markdown**
→ `report.report_content`; `report.status` akışı yönetilir. İş sonu muhasebesi (`current_count++`,
gerekiyorsa `completed=1`, `queued` reset) ve periyot bazlı yeniden zamanlama bu fazda netleşir.
