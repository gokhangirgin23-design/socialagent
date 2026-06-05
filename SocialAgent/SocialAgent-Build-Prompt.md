# SocialAgent — Build Prompt (Claude Code) — v2

> Projeyi sıfırdan kurmak için Claude Code'a verilecek **çalıştırma prompt'u**.
> Tek seferde her şeyi isteme — **fazları sırayla** uygula; her faz sonunda dur, derle, doğrula.
> Tüm kural/şema/DevOps için **`CLAUDE.md`**'yi kaynak kabul et.
> v2: D1–D5 kararları kilitli, `gokhangirgin23-design/airepo` mimarisi entegre.

---

## ROL & BAĞLAM

Sen kıdemli bir Spring Boot mimarısın. **SocialAgent** adında **Spring Boot 4.0.6 / Java 21 / Maven**
backend projesi kuracaksın. Proje hafızası `CLAUDE.md`'de; **KESİN MİMARİ KURALLAR** bağlayıcıdır.

Kritik hatırlatmalar (detay CLAUDE.md'de):
- Tüm endpoint **POST**, tüm response **BaseResponse** extend eder, data yoksa **null**, HTTP daima **200**.
- **Service interface yok.** **Entity ilişkisi yok**; join'ler **`JdbcTemplate` native + eski stil `=`** (airepo `loginNative` stili).
- userId **JWT'den**. Unique'ler **service'te de** kontrol. Çoğu satırda **Türkçe yorum**.
- Local **H2**, test/prod **PostgreSQL** (`ddl-auto: none`, şema env bazlı), **Flyway** + **MapStruct** + **LangChain4j**.
- Kök URL → **Swagger redirect**. `.project` + `.classpath` üret.
- **DevOps airepo'ya birebir uyumlu** (paket kökü `com.api`, springdoc 2.8.9, actuator+prometheus, Dockerfile/compose/CI — CLAUDE.md Bölüm 13).

Her faz sonunda: derlenebilir kod + kısa "ne yaptım / sıradaki faz" özeti.

---

## FAZ 0 — İskelet & DevOps (airepo hizalı)
- Modül klasörü `SocialAgent/` + `mvnw` wrapper. `pom.xml`: parent `spring-boot-starter-parent` 4.0.6, java 21; airepo deps + (security, h2, flyway, mapstruct, langchain4j openai+gemini, mail). Sürümleri Boot 4 uyumlu **pinle**.
- Paket yapısı (CLAUDE.md Bölüm 15, kök `com.api`).
- `application.yml` (port 8080, forward-headers, actuator health/info/prometheus) + `application-local.yml` (H2) + `application-test/prod.yml` (PostgreSQL `currentSchema={env}_schema`, RabbitMQ vhost `/{env}`, `ddl-auto: none`).
- `Dockerfile`, `docker-compose.test.yml`, `docker-compose.prod.yml`, `.github/workflows/deploy-test.yml` + `deploy-prod.yml` (CLAUDE.md Bölüm 13 birebir; dizin `/opt/myapp/{env}/SocialAgent`).
- Eclipse `.project` + `.classpath`.
- `common`: `BaseResponse`, `ResponseCode` enum (Bölüm 6), `@RestControllerAdvice` (gerçek exception → uygun code, HTTP 200 hedefi).
- Swagger config + kök `/` → `/swagger-ui.html` redirect.
- Flyway `V1__init_schema.sql`: **tüm tablolar** (Bölüm 5), UUID PK, unique'ler, ortak alanlar. H2 + PostgreSQL uyumlu.
- **DoD:** `./mvnw clean install` geçer; `local` profille ayağa kalkar; kök URL Swagger'a yönlenir.

## FAZ 1 — Güvenlik & Auth
- Spring Security (Boot 4 → Security 7) + JWT filter (stateless).
- **Google SSO:** id_token doğrula → `user_info` upsert (`google_id`/`email` unique service kontrolü).
- Access + **refresh token**; `refresh_token` tablosu; refresh endpoint.
- `SecurityContext`'ten userId util.
- **DoD:** login (POST) access+refresh; refresh (POST) yeni access; korumalı endpoint token'sız reddedilir.

## FAZ 2 — Onboarding
- `sector` / `subsector` listele (POST), kullanıcı `sector_id`/`subsector_id` güncelle.
- `user_social_account` ekle (TEK hesap; UNIQUE service kontrolü).
- `monitored_account` upsert + `user_monitored_account` bağla (UNIQUE'ler service'te).
- Tüm join'ler **JdbcTemplate native + `=`**.
- **DoD:** onboarding baştan sona POST'larla biter; duplicate'ler service'te yakalanır.

## FAZ 3 — Job Oluşturma
- `user_job` insert; `analysis_mode` türet (OWN_ONLY/COMPETITOR_ONLY/BOTH/NONE); `ON_DEMAND`'da repeat istenmez.
- **DoD:** 4 mode doğru `analysis_mode`; ON_DEMAND repeat istemiyor.

## FAZ 4 — Scheduler + RabbitMQ
- `@Scheduled` → `active=1 AND completed=0` (JdbcTemplate) → RabbitMQ producer (vhost env bazlı).
- **DoD:** uygun job'lar kuyruğa düşer.

## FAZ 5 — Worker + Apify
- RabbitMQ consumer (worker). Apify REST client.
- **Top 5 (D1):** sektör/alt-sektör adı → Apify keyword araması → follower/engagement'a göre sırala → ilk 5 → `platform_sector`+`account_name_sector`.
- Mode'a göre hesap seçimi (CLAUDE.md Bölüm 10).
- **Tekrar-analiz koruması:** Apify'dan önce `post_analysis` JOIN; son `analysis_period_days` içinde varsa çekme.
- Postlar → `social_post` (UNIQUE(platform, platform_post_id) service kontrolü).
- **DoD:** her mode doğru hesap kümesinden son 5 post yazar; 1-hafta kuralı çalışır.

## FAZ 6 — AI Analiz (LangChain4j)
- Yönlendirme: TEXT/caption → OpenAI; IMAGE/VIDEO/CAROUSEL → Gemini Vision.
- `ai/prompt` template'leri. Dönen JSON → `post_analysis.analysis_json`.
- **DoD:** post başına analiz JSON'ı üretilip kaydedilir.

## FAZ 7 — Rapor
- Tüm analiz JSON'larını topla → OpenAI rapor prompt'u → **Markdown** → `report.report_content`; status akışı.
- İş sonu: `current_count++`, gerekiyorsa `completed=1`.
- **DoD:** job tamamlanınca COMPLETED Markdown rapor.

## FAZ 8 — Bildirim & Dashboard
- Rapor hazır → mail + push → `notification` insert.
- Dashboard: kullanıcı bazlı rapor (POST + body), **paging**, userId JWT'den.
- **DoD:** bildirim kaydı oluşur; dashboard sayfalı liste döner.

---

## GENEL KABUL KRİTERLERİ (her faz)
- [ ] `./mvnw clean install` hatasız.
- [ ] Hiçbir endpoint GET/PUT/DELETE değil — hepsi POST.
- [ ] 200 dışında kod yok (gerçek exception hariç); boş data null.
- [ ] userId hep JWT'den.
- [ ] Service interface yok; entity ilişkisi yok; join'ler JdbcTemplate native + `=`.
- [ ] DB unique'leri service'te de kontrol ediliyor.
- [ ] Türkçe yorumlar var.
- [ ] DevOps dosyaları airepo deseniyle uyumlu.

---

## BAŞLA
D1–D5 kilitli (CLAUDE.md Bölüm 14). **FAZ 0** ile başla. Boot 4 + LangChain4j/Flyway/MapStruct/Security
sürüm uyumunda takılırsan sürümü pinle ve `// TODO(uyum): ...` notu bırak, durma.
