# CLAUDE.md — SocialAgent Backend (Proje Hafızası / Spec) — v2

> Bu dosya projenin **kalıcı hafızası**dır. Claude Code her oturumda bunu okur.
> Buradaki kurallar **bağlayıcıdır**; kod yazarken hiçbiri ihlal edilmez.
> Tüm kod yorumları **Türkçe** ve çoğu satırda olacak.
> v2 notu: D1–D5 kararları kilitlendi, `gokhangirgin23-design/airepo` DevOps + kod konvansiyonu entegre edildi.

---

## 1. Proje Özeti

**SocialAgent**, sosyal medya hesaplarını (kendi + rakip) Apify ile çekip AI ile analiz eden,
kullanıcıya Markdown rapor + bildirim üreten bir backend API'dir. **Hem web hem mobil** erişir.

Akış:
`Register (Google SSO) → Sektör/Alt sektör → Kendi (tek) & rakip hesaplar (opsiyonel)
→ Job oluştur → Scheduler → RabbitMQ → Worker → Apify → AI analiz → Rapor → Bildirim → Dashboard`

---

## 2. Teknoloji Yığını (airepo ile hizalı)

| Katman | Teknoloji | Not |
|---|---|---|
| Dil / Build | **Java 21**, Maven (mvnw wrapper) | airepo ile aynı |
| Framework | **Spring Boot 4.0.6** (starter-parent) | airepo parent sürümü |
| groupId / paket | `com.api` kökü | airepo konvansiyonu |
| Güvenlik | Spring Security (Boot 4 → Security 7) + JWT | Google SSO + refresh token |
| DB erişim | `spring-boot-starter-data-jpa` + **`JdbcTemplate`** | join'ler JdbcTemplate native |
| DB | PostgreSQL (test/prod), H2 (local) | profile bazlı, `ddl-auto: none` |
| Migration | **Flyway** | tüm şema migration ile |
| DTO Mapping | **MapStruct** | |
| AI | **LangChain4j** (OpenAI + Google Gemini modülleri) | |
| Scraping | **Apify** REST API (HTTP client) | Instagram aktörleri |
| Mesajlaşma | RabbitMQ (`spring-boot-starter-amqp`) | vhost env bazlı |
| Dokümantasyon | `springdoc-openapi-starter-webmvc-ui` 2.8.9 | |
| Monitoring | actuator + micrometer-prometheus | airepo'da var |
| Bildirim | Spring Mail + Push (FCM vb.) | |
| Yardımcı | Lombok (`@Getter/@Setter/@Builder/@RequiredArgsConstructor`) | airepo kullanıyor |
| IDE | Eclipse | `.project` + `.classpath` üretilecek |

> ⚠️ **Boot 4 uyum notu:** Spring Boot 4.0.6 görece yeni. LangChain4j, Flyway, MapStruct ve Security
> sürümleri Boot 4 ile uyumlu olacak şekilde `pom.xml`'de **pinlenir** (gerekirse LangChain4j starter
> yerine manuel bean wiring). Security 7 DSL'i Security 6'dan farklıdır; Boot 4'ün yönettiği sürüm kullanılır.

---

## 3. KESİN MİMARİ KURALLAR (asla ihlal edilmez)

1. **Katmanlar:** `controller → service → repository → entity`. **Service interface YOK** (sadece concrete `XxxService`).
2. **Tüm endpoint'ler POST** (dashboard sorguları dahil — POST + body).
3. **Generic response zarfı:** her response `BaseResponse` extend eder → `responseCode` (Integer) + `responseDescription` (String). Data yoksa **null**. **404 yok.** Gerçek Java exception dışında **tüm HTTP kodları 200**; iş sonucu `responseCode` ile taşınır.
4. **userId daima JWT'den** (`SecurityContext`). Request'ten userId **asla** okunmaz.
5. **Her unique constraint service katmanında da elle kontrol edilir** (insert öncesi sorgu).
6. **İlişkili tablolar native query ile çekilir** → **`JdbcTemplate`** + text-block SQL + `?` param (airepo `loginNative` stili). Join'ler **eski stil `=`**: `FROM a, b WHERE a.id = b.a_id`. Entity'lerde foreign key sadece **ID kolonu** (ör. `sectorId`), nesne referansı / `@ManyToOne` yok.
7. **Türkçe yorum** çoğu satırda.
8. Kök URL (`host:port/`) → **Swagger UI redirect**.
9. **DevOps:** airepo mimarisine uygun (Bölüm 13).

---

## 4. Profile Yapısı

| Profile | DB | Şema/Not |
|---|---|---|
| `local` | H2 (MODE=PostgreSQL) | hızlı geliştirme, Flyway çalışır |
| `test` | PostgreSQL | `currentSchema=test_schema`, RabbitMQ vhost `/test` |
| `prod` | PostgreSQL | `currentSchema=prod_schema`, RabbitMQ vhost `/prod` |

- `application.yml` ortak (port 8080, `forward-headers-strategy: framework`, actuator: health/info/prometheus).
- `application-{profile}.yml` override. Test/prod'da DB & RabbitMQ env değişkenlerinden (`${DB_HOST}` vb.).
- `ddl-auto: none` (şema Flyway ile). SQL H2 + PostgreSQL uyumlu yazılır.

---

## 5. Veritabanı Şeması (kaynak: DB Schema PDF)

> Ortak alanlar her tabloda: `active`, `created_date`, `updated_date`. Tüm PK'ler **UUID**.

- **user_info:** `user_id(PK)`, `google_id(UNIQUE)`, `email(UNIQUE)`, `full_name`, `profile_photo_url`, `sector_id`, `subsector_id`
- **refresh_token:** `refresh_token_id(PK)`, `user_id`, `token`, `expire_date`
- **sector:** `sector_id(PK)`, `name`
- **subsector:** `subsector_id(PK)`, `sector_id`, `name` — **UNIQUE(sector_id, name)**
- **user_social_account:** `user_social_account_id(PK)`, `user_id`, `platform`, `account_name`, `profile_url` — **UNIQUE(user_id, platform, account_name)**
- **monitored_account:** `monitored_account_id(PK)`, `platform`, `account_name` — **UNIQUE(platform, account_name)**
- **user_monitored_account:** `user_monitored_account_id(PK)`, `user_id`, `monitored_account_id` — **UNIQUE(user_id, monitored_account_id)**
- **user_job:** `user_job_id(PK)`, `user_id`, `selected_user_social_account_id`, `analysis_mode`, `job_period`, `analysis_period_days`, `repeat_count`, `current_count`, `completed`
- **social_post:** `social_post_id(PK)`, `user_job_id`, `monitored_account_id(nullable)`, `platform_sector(nullable)`, `account_name_sector(nullable)`, `platform`, `platform_post_id`, `post_url`, `caption`, `hashtags`, `media_url`, `media_type`, `likes_count`, `comments_count`, `views_count`, `shares_count`, `post_date` — **UNIQUE(platform, platform_post_id)**
- **post_analysis:** `post_analysis_id(PK)`, `social_post_id`, `analysis_json (JSONB/CLOB)`
- **report:** `report_id(PK)`, `user_job_id`, `status`, `report_content (Markdown)`
- **notification:** `notification_id(PK)`, `user_id`, `title`, `message`, `reference_type`, `reference_id`, `is_read`

---

## 6. Enum / Sabit Değerler

```
analysis_mode    : OWN_ONLY | COMPETITOR_ONLY | BOTH | NONE
job_period       : DAILY | WEEKLY | MONTHLY | ON_DEMAND     (ON_DEMAND = anlık, repeat yok)
platform         : INSTAGRAM
media_type       : IMAGE | VIDEO | CAROUSEL | TEXT
report.status    : PENDING | GENERATING | COMPLETED | FAILED
notification.reference_type : REPORT | JOB
completed/active : 0 | 1
```

**Response code registry (D5 — onaylandı):**
```
001 SUCCESS
002 VALIDATION_ERROR
003 DUPLICATE / UNIQUE_VIOLATION
004 UNAUTHORIZED / TOKEN_INVALID
005 NOT_FOUND (iş anlamında; HTTP yine 200)
999 SYSTEM_ERROR
```

---

## 7. Onboarding Akışı & Endpoint'ler (hepsi POST, userId JWT'den)

1. **Register/Login (Google SSO):** id_token doğrula → `user_info` upsert (`google_id`/`email` unique kontrolü) → access + refresh üret → `refresh_token` kaydet.
2. **Refresh token:** geçerli refresh ile yeni access.
3. **Sektör seç:** `sector` listele + kullanıcı `sector_id` güncelle.
4. **Alt sektör seç:** `subsector` (sector_id'ye göre) + `subsector_id` güncelle.
5. **Kendi hesabını ekle (opsiyonel, TEK hesap):** `user_social_account` (UNIQUE(user_id, platform, account_name) service kontrolü).
6. **Takip hesaplarını ekle (opsiyonel):** `monitored_account` upsert (UNIQUE(platform, account_name)) + `user_monitored_account` bağla (UNIQUE(user_id, monitored_account_id)).
7. **Rapor oluştur → `user_job` insert** (Bölüm 8).

---

## 8. Job Oluşturma Kuralları

- **analysis_mode** seçime göre: kendi(tek) → `OWN_ONLY`, rakip → `COMPETITOR_ONLY`, ikisi → `BOTH`, hiçbiri → `NONE`.
- **job_period:** `ON_DEMAND` → anlık, `repeat_count` istenmez. Diğerlerinde `repeat_count` girilir, `current_count=0`.
- `analysis_period_days`: tekrar-analiz penceresi (varsayılan 7).
- Insert sonrası `active=1`, `completed=0`.

---

## 9. Scheduler + Queue + Worker

1. **`@Scheduled`** → `user_job` içinde `active=1 AND completed=0` olanları JdbcTemplate ile çek.
2. RabbitMQ producer ile kuyruğa koy (mesaj = `user_job_id`). vhost env bazlı.
3. **Worker (consumer)** → Apify + AI pipeline.
4. İş sonu: `current_count++`; `current_count >= repeat_count` ise `completed=1`. `ON_DEMAND` tek seferde `completed=1`.

---

## 10. Apify Veri Çekme Kuralları (D1 — onaylandı: Apify keyword search)

`analysis_mode`'a göre **son 5 gönderi** çekilecek hesaplar:

| Mode | Hesaplar |
|---|---|
| `NONE` | sektörün **top 5 Instagram** hesabı |
| `OWN_ONLY` | sektör top 5 + kullanıcının kendi (tek) hesabı |
| `COMPETITOR_ONLY` | sadece rakip (monitored) hesaplar |
| `BOTH` | seçilen hesaplar (kendi + rakip) |

**Sektör "top 5" nasıl belirlenir (D1):**
1. Kullanıcının `sector`/`subsector` adını **keyword** olarak Apify'ın keyword/profil arama aktörüne ver
   (ör. Instagram Search Scraper / keyword-based profile finder). Apify'da "hazır top 5" yoktur;
   keyword araması yapılır.
2. Dönen profilleri **follower_count / engagement_rate**'e göre sırala, **ilk 5**'i seç.
3. Bu hesaplar `social_post.platform_sector` + `account_name_sector` alanlarına yazılır (kolonlar bunun için).

**TEKRAR-ANALİZ KORUMASI (kritik):** Apify çağrısından önce `post_analysis` JOIN'lenir; son
`analysis_period_days` (≈1 hafta) içinde o hesap analiz edilmişse **Apify'dan çekilmez**, mevcut veri kullanılır.

Çekilen postlar → `social_post` (UNIQUE(platform, platform_post_id) service kontrolü).

---

## 11. AI Analiz Pipeline (LangChain4j) — (D3 onaylandı)

1. `social_post` `media_type`'a göre yönlenir:
   - **caption / TEXT** → **OpenAI**.
   - **IMAGE / VIDEO / CAROUSEL** → **Gemini Vision**.
2. `ai/prompt` paketinde iyi prompt template'leri (engagement, tema, ton, hashtag analizi).
3. Dönen **JSON** → `post_analysis.analysis_json`.
4. Tüm analiz JSON'ları toplanır → **OpenAI**'a rapor prompt'u → **Markdown** → `report.report_content`. `report.status`: PENDING → GENERATING → COMPLETED/FAILED.

---

## 12. Bildirim & Dashboard

- Rapor hazır → **mail + push** → `notification` insert (`reference_type=REPORT`, `reference_id=report_id`, `is_read=0`).
- **Dashboard:** kullanıcı bazlı rapor sorgu (POST + body), **paging** (page/size), userId JWT'den.

---

## 13. DevOps Mimarisi (airepo — D4 onaylandı, repo incelendi)

**Proje yapısı:** Repo kökünde uygulama modülü klasörü (airepo'da `AIBackendApi/` → burada `SocialAgent/`). İçinde `mvnw`, `pom.xml`, `Dockerfile`, `docker-compose.{test,prod}.yml`, `src/`.

**pom.xml temeli:** `spring-boot-starter-parent` **4.0.6**, `java.version=21`, plugin `spring-boot-maven-plugin`. airepo deps (starter, web, data-jpa, jdbc, amqp, actuator, micrometer-prometheus, postgresql runtime, lombok, springdoc 2.8.9) + SocialAgent ek deps (security, h2, flyway, mapstruct, langchain4j openai+gemini, mail).

**application.yml (ortak):**
```yaml
server:
  port: 8080
  forward-headers-strategy: framework
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: always
```

**application-{test,prod}.yml:** PostgreSQL `jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}?currentSchema={env}_schema`, RabbitMQ vhost `/{env}`, `jpa.hibernate.ddl-auto: none`, `show-sql: true`. **application-local.yml:** H2 (MODE=PostgreSQL), Flyway aktif.

**Dockerfile:**
```dockerfile
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**docker-compose.{test,prod}.yml:** env başına **2 instance** (`app1`, `app2`), `build: .`, `env_file: /opt/myapp/{env}/.env`, `restart: always`, `SPRING_PROFILES_ACTIVE`. Portlar: **prod 8081/8082**, **test 9081/9082**.

**CI/CD (.github/workflows/deploy-{test,prod}.yml):** `appleboy/ssh-action`; **main → prod**, **develop → test**. VPS adımları: `git fetch` + `git reset --hard origin/{branch}` → `chmod +x mvnw` → `./mvnw clean package -DskipTests` → `docker-compose -p {env} -f docker-compose.{env}.yml down && build --no-cache && up -d`. Hedef dizin `/opt/myapp/{env}/SocialAgent`. Secrets: `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`.

---

## 14. Karar Günlüğü (hepsi KİLİTLİ ✅)

| # | Karar |
|---|---|
| D1 | "Top 5" Apify **keyword araması** (sektör/alt-sektör adı) → follower/engagement'a göre sırala → ilk 5 |
| D2 | Kullanıcı **tek** kendi hesabı seçer; `selected_user_social_account_id` tekil kalır |
| D3 | caption/TEXT → OpenAI; IMAGE/VIDEO/CAROUSEL → Gemini Vision |
| D4 | DevOps + kod konvansiyonu airepo'ya uygun (Bölüm 13) |
| D5 | Response code registry (Bölüm 6) |

---

## 15. Paket Yapısı (airepo konvansiyonu + SocialAgent ekleri)

```
com.api
├── config        (security, swagger, rabbit, langchain4j, async, mail)
├── security       (jwt, google sso, filter)
├── common         (BaseResponse, ResponseCode, GlobalExceptionHandler)
├── controller
├── service         (concrete; JdbcTemplate native join'ler burada)
├── entity          (ilişkisiz; sadece ID kolonları; @Entity + @Id)
├── dto
│   └── repository  (JpaRepository'ler — airepo konvansiyonu)
├── mapper          (MapStruct)
├── ai              (langchain4j servisleri + prompt/ template'leri)
├── apify           (REST client + actor çağrıları)
├── messaging       (rabbit producer / consumer / worker)
└── scheduler
src/main/resources/db/migration   (Flyway V1__init.sql ...)
```
