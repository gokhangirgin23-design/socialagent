# SocialAgent — Backend API (FAZ 1: Güvenlik & Auth)

Sosyal medya analiz & raporlama backend'i. Bu paket **FAZ 0 (iskelet)** + **FAZ 1 (Güvenlik & Auth)**'u içerir.
Mimari ve kurallar için `CLAUDE.md`'ye, faz planı için `SocialAgent-Build-Prompt.md`'ye bakın.

## FAZ 1'de ne eklendi?
- **Spring Security (Boot 4 → Security 7) + JWT filter** — stateless, `Authorization: Bearer <token>`.
- **Google SSO**: `POST /auth/google` → id_token doğrula → `user_info` upsert (google_id/email **unique elle kontrol**) → access + refresh döner.
  - Doğrulama `GoogleTokenVerifier` ile (imza + audience + expiry). Local'de `app.google.verification-enabled=false` → imzasız payload (dev kolaylığı).
- **Refresh token**: opak (rastgele 256-bit), `refresh_token` tablosunda saklanır. `POST /auth/refresh` → yeni access.
- **`SecurityUtil.getCurrentUserId()`** — userId **daima JWT'den** (Madde 4); istekten asla okunmaz.
- Korumalı örnek uç: **`POST /user/me`** (token'daki kullanıcıyı döner).
- Yetkisiz erişim → `RestAuthenticationEntryPoint` → **BaseResponse `responseCode=4`**, HTTP yine **200** (Madde 3).
- Native lookup'lar `JdbcTemplate` + text-block SQL + `?` param (airepo `loginNative` stili); insert/update `JpaRepository.save()`.
- `UserMapper` (MapStruct) `UserInfo` → `UserDto`.

### Yeni paket/dosyalar
```
config/AppProperties.java                 (app.jwt.* + app.google.*)
config/SecurityConfig.java                 (güncellendi: JWT filter + /auth/** açık)
security/JwtService.java                    (access JWT üret/doğrula - jjwt 0.12.x)
security/JwtAuthenticationFilter.java       (OncePerRequestFilter)
security/GoogleTokenVerifier.java           (id_token doğrulama + local bypass)
security/GoogleUserData.java                (record)
security/SecurityUtil.java                  (SecurityContext'ten userId)
security/RestAuthenticationEntryPoint.java  (004 + HTTP 200)
entity/UserInfo.java, entity/RefreshToken.java
dto/repository/UserInfoRepository.java, RefreshTokenRepository.java
dto/GoogleLoginRequest, RefreshTokenRequest, UserDto, LoginResponse, RefreshResponse
mapper/UserMapper.java
service/AuthService.java                    (login + refresh + upsert, native lookup)
controller/AuthController.java, controller/UserController.java
```

## Çalıştırma (local)
```bash
# Wrapper yoksa bir kez üret (veya Eclipse import et):
mvn -N wrapper:wrapper
./mvnw spring-boot:run
```
- Uygulama: http://localhost:8080 → **Swagger UI**
- H2 console: http://localhost:8080/h2-console (`jdbc:h2:mem:socialagent`)

## Auth akışını test etme (local)
Local'de Google imza doğrulaması KAPALI olduğu için elle bir "fake id_token" üretmek yeterli
(JWT'nin sadece payload'u base64url decode edilir, `sub` zorunlu):

```bash
# 1) Sahte id_token payload'u (header.payload.signature; imza önemsiz, "x" yeterli)
PAYLOAD=$(printf '{"sub":"google-123","email":"test@example.com","name":"Test Kullanici","picture":"https://x/p.png"}' \
  | base64 | tr '+/' '-_' | tr -d '=')
FAKE_TOKEN="eyJhbGciOiJSUzI1NiJ9.${PAYLOAD}.x"

# 2) Login → access + refresh
curl -s -X POST http://localhost:8080/auth/google \
  -H 'Content-Type: application/json' \
  -d "{\"idToken\":\"${FAKE_TOKEN}\"}"

# 3) Korumalı uç token'sız → responseCode=4 (HTTP 200)
curl -s -X POST http://localhost:8080/user/me

# 4) Korumalı uç token ile → kullanıcı bilgisi
curl -s -X POST http://localhost:8080/user/me \
  -H "Authorization: Bearer <login'den dönen accessToken>"

# 5) Refresh → yeni access
curl -s -X POST http://localhost:8080/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"<login'den dönen refreshToken>\"}"
```

## FAZ 1 DoD karşılığı
- [x] `POST /auth/google` access + refresh döner
- [x] `POST /auth/refresh` yeni access döner
- [x] Korumalı uç token'sız reddedilir (responseCode=4, HTTP 200)
- [x] Tüm uçlar **POST**, response **BaseResponse**, userId **JWT'den**
- [x] Unique'ler service'te elle kontrol; join/lookup `JdbcTemplate` native + `?`

## ⚠️ Önemli notlar
- Bu kod **derlenmeden** üretildi (Maven Central erişimi olmayan ortam). İlk `mvn clean install`'da
  ufak sürüm/import düzeltmeleri gerekebilir:
  - `google-api-client` sürümü (`pom.xml` → `google.api.client.version`) ve `langchain4j` Boot 4 uyumu (`// TODO(uyum)`).
  - Spring Security 7 DSL'inde değişiklik olursa `SecurityConfig`.
- **PROD/TEST**: `JWT_SECRET` (≥32 karakter) ve `GOOGLE_CLIENT_ID` env zorunlu; `GOOGLE_VERIFICATION_ENABLED` **true** kalmalı.
- `mvnw` scriptleri pakette yoksa `mvn -N wrapper:wrapper` ile üretin.

## Sonraki adımlar
- **FAZ 2**: Onboarding (sector/subsector/social/monitored) — JdbcTemplate native join'ler.
- ... (FAZ 8'e kadar — `SocialAgent-Build-Prompt.md`)

> Token verimli ilerleme: bir sonraki fazı **temiz bir oturumda** iste; `CLAUDE.md` + bu paketi zip'leyip yükle.
