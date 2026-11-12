# 🛡️ DORO 게이트웨이 라우팅 규칙

본 문서는 DORO 플랫폼의 게이트웨이(nginx)에 서비스를 추가하거나 API 경로를 바꿀 때 생기는 라우팅 충돌·누락을 막기 위한 에이전트/개발자 필수 규칙이다. **기준은 `gateway/nginx.conf`(게이트웨이)와 `web/nginx.conf`(포털 컨테이너 내부)이며, 이 문서와 설정이 다르면 설정이 맞다.**

- 검증 기준 커밋: `12d6582` (2026-10-02), 설정 파일을 전부 읽고 작성. 실제 서버에 배포된 설정과의 일치 여부는 `[미검증]`(§8 참고).
- **2026-10-09 주소 개편**: 메인(`/`)은 허브, 블로그는 `/blog/`. 서버 설정과 `gateway/nginx.conf` 를 맞췄다(서버 값이 기준). 반영 절차와 라우팅 시험은 `apply-gateway-blog.sh`, `gateway_blog_switch.py`, `scripts/test-gateway-routing.sh`(기대 목록 `scripts/gateway-routing/`)에 있다.
- 인증·인가 연동 사실은 Doro 저장소의 `docs/DORO_AGENT_GUIDE.md` 를, 브라우저 로그인(BFF) 구조는 이 저장소의 `docs/BFF.md` 를 본다.
- **라우팅 회귀 시험**: `scripts/test-gateway-routing.sh gateway/nginx.conf scripts/gateway-routing/expect-current.txt` 가 설정의 location 매칭을 기대 목록(경로 → 업스트림)과 비교한다. CI(`deploy.yml`)가 배포 전에 실행하므로 location 을 바꾸면 `expect-current.txt` 도 함께 고친다.

---

## 1. 진입점과 서버 구조

- **실제 진입점은 443(HTTPS, TLS 1.2/1.3, HTTP/2)이다.** 모든 서비스가 이 한 개의 origin 으로 묶인다.
- **80 은 진입점이 아니다.** `/.well-known/acme-challenge/`(인증서 발급 검증, 정적 `root`)만 직접 응답하고, 나머지는 전부 `301 https://$host$request_uri` 로 리다이렉트한다.
- 컨테이너 내부 loopback 전용 서버 `127.0.0.1:8099` 가 있다(`204` 만 반환). 외부에 노출되지 않고, `/csp-report` 의 요청 본문을 로그로 남기기 위한 프록시 대상이다.
- 게이트웨이는 docker 네트워크 이름으로 업스트림에 접속한다(호스트 포트 불필요). 게이트웨이 컨테이너는 **compose 프로젝트 밖**이며 같은 docker 네트워크에 붙어 있어야 한다.
- 이 서버 블록에는 `server_name` 으로 공개 도메인, `localhost`, `_`(그 외 전부)이 지정돼 있다. 인증서는 서버의 Let's Encrypt 경로를 읽는다(경로는 `gateway/nginx.conf` 참고).

### 업스트림

| upstream | 대상 | 용도 |
|---|---|---|
| `portal_upstream` | `web:80` | 포털(통합 계정 센터) SPA |
| `iam_upstream` | `auth-api:8080` | IAM(인증, OAuth/OIDC, 관리자 API) |
| `blog_web_upstream` | `doro-blog-web:80` | 블로그 프런트엔드(`/blog/` 아래) |
| `blog_api_upstream` | `doro-blog-backend:8082` | 블로그 백엔드 API |
| `minio_upstream` | `doro-minio:9000` | 업로드 미디어(MinIO) |
| `loki_upstream` | 중앙 Loki(노트북, IP 로 지정) | 로그 조회(Loki). 미니의 loki 컨테이너는 없앴다(`apply-gateway-loki.sh`). nginx 는 시작 때 이름을 해석하므로 컨테이너 이름이 아니라 IP 로 가리킨다 |
| `menu_upstream` | `doro-menu:80` | 도로메뉴 서브 서비스 |
| `party_web_upstream` / `party_api_upstream` | `doro-party-web:80` / `doro-party-api:8086` | 도로 파티(`/party/`) 프런트엔드 / 백엔드 |
| `games_web_upstream` / `games_api_upstream` | `doro-games-web:80` / `doro-games-api:8087` | 도로 게임 천국(`/games/`) 프런트엔드 / 백엔드 |
| `notify_api_upstream` | `doro-notify-api:8089` | 플랫폼 알림 서비스(API 만, web 없음). 내부 API(`/internal/v1/**`)는 라우팅하지 않는다 |
| `bff_upstream` | `doro-bff-api:8085` | 브라우저 로그인·세션을 서비스 밖에서 맡는 BFF([`BFF.md`](BFF.md), §2.4) |

**Guard(`guard-api`)는 업스트림에 없다. Guard 는 게이트웨이로 프록시하지 않는다**(8081/9090 은 내부 전용, §5).

---

## 2. location 목록과 매칭 우선순위

nginx 의 평가 순서(이 순서를 모르고 location 을 추가하면 반드시 충돌한다):

1. 서버 수준 `rewrite ^(/api/v1/.*?)/+$ $1;` 이 **먼저** 실행된다 — `/api/v1/...` 끝의 슬래시를 제거한다(Spring 이 깔끔하게 매칭되도록).
2. **정확 일치(`= /path`)** 가 있으면 즉시 확정.
3. 없으면 **가장 긴 프리픽스** 를 기억해 두고(`^~` 가 아니면),
4. **정규식 location 을 설정 파일 순서대로** 검사해 **처음 맞는 것이 프리픽스를 이긴다.** 맞는 정규식이 없으면 기억해 둔 가장 긴 프리픽스를 쓴다.

즉 정규식 location(`sessions`, `admin/users`, 포털 라우트, PWA·로고 파일)은 **프리픽스보다 우선**한다. 같은 종류끼리는 프리픽스는 길이, 정규식은 파일 순서가 결정한다.

### 2.1 정확 일치 (`=`)

| location | 동작 |
|---|---|
| `= /csp-report` | **POST 만 허용**(`limit_except`), 속도 제한 30회/분(burst 20), 본문 4KB 제한, 본문을 `CSP-REPORT` 접두어 로그로 stdout 에 기록 → `127.0.0.1:8099`(204). Loki 로 수집됨 |
| `= /_auth_admin` | **`internal`**(외부 접근 불가). `/loki/` 의 `auth_request` 전용. IAM `GET /api/v1/admin/authz` 로 보내되 본문을 전달하지 않고 `Authorization` 헤더와 `X-Real-IP` 만 넘긴다 |
| `= /api/v1/users/me/password` | → `iam_upstream` (비밀번호 변경) |
| `= /api/v1/users/me/deletion` | → `iam_upstream` (회원탈퇴 요청). 없으면 `/api/v1/` 로 흘러 블로그가 받는다 |
| `= /api/v1/users/me` | → **`$users_me_upstream`**(referer/메서드 맵, §3) |
| `= /oauth2/consent` | → **`portal_upstream`**. OAuth 동의 화면은 포털 SPA 가 렌더링한다. 정확 일치라 아래 `/oauth2/` 프리픽스(IAM)보다 우선한다. 브라우저 흐름: IAM `/oauth2/authorize` → 302 `/oauth2/consent?...` → 포털 |
| `= /shield.svg` | → `portal_upstream/shield.svg` (포털 아이콘) |
| `= /menu` | `301 /menu/` |

### 2.2 프리픽스 (가장 긴 것이 선택됨)

| location | 대상 | 비고 |
|---|---|---|
| `/media/` | `minio_upstream/doro-blog-media/` | 업로드 미디어. **sandbox CSP** 별도 적용(§4.3) |
| `/loki/` | `loki_upstream/loki/` | **관리자 전용**: `auth_request /_auth_admin`, **GET/HEAD 만 허용**(`limit_except`, 나머지 메서드는 거부). 쓰기(push)는 서버별 수집기(Alloy)가 중앙 Loki(노트북)에 직접 수행(이 경로로는 쓰지 않는다). 이 location 이 없으면 중앙 관제 로그 화면이 비게 된다 |
| `/api/v1/auth/` | `iam_upstream` | 로그인/가입/2FA/로그아웃/토큰 갱신 등. **끝 슬래시까지 포함한 프리픽스**라 `/api/v1/authors` 처럼 이름만 비슷한 경로는 걸리지 않는다 |
| `/api/v1/admin/oauth/` | `iam_upstream` | OAuth 클라이언트 관리 API(등록·조회·비활성화). 슬래시까지 포함해 이 경로만 IAM 으로 보낸다 |
| `/api/v1/` | `blog_api_upstream/api/v1/` | **블로그 백엔드(나머지 전부)**. 위의 IAM 전용 경로보다 짧아서 IAM 쪽이 이기고, 그 외는 여기로 온다. BFF 모드의 `auth_request` 가 붙는다(§2.4) |
| `/iam/` | `iam_upstream/` | `/iam` 접두어를 떼고 IAM 으로 전달(IAM 직접 접근용) |
| `/oauth2/` | `iam_upstream/oauth2/` | OAuth/OIDC(`authorize`, `token`, `userinfo`). `= /oauth2/consent` 만 예외(포털) |
| `/.well-known/` | `iam_upstream/.well-known/` | JWKS, OIDC discovery |
| `/blog/api/` | `blog_api_upstream/api/` | 블로그 API(새 주소). `/blog` 접두어를 떼고 전달. 쓰기 제한(`blog_writes`) 적용, BFF `auth_request` 가 붙는다(§2.4). 업로드(`/blog/api/v1/uploads`)는 별도 location 에서 더 엄격한 제한 |
| `/party/api/`, `/party/` | `party_api_upstream/api/`, `party_web_upstream` | 도로 파티. API 는 BFF `auth_request`(§2.4), 웹은 서비스 전용 CSP. `= /party` 는 `/party/` 로 301 |
| `/games/api/`, `/games/` | `games_api_upstream/api/`, `games_web_upstream` | 도로 게임 천국. 구성은 파티와 같다. `= /games` 는 `/games/` 로 301 |
| `/notify/api/v1/` | `notify_api_upstream/api/v1/notify/` | 알림 서비스, 허브용. **Bearer 토큰을 그대로 전달**(auth_request 없음). 클라이언트가 보낸 `X-Doro-Notify-App` 은 빈 값으로 덮어써 보내지 않는다 |
| `/party/api/v1/notify/`, `/blog/api/v1/notify/`, `/games/api/v1/notify/` | `notify_api_upstream/api/v1/notify/` | 알림 서비스, 앱 화면용. 각 앱의 `auth_request /_bff_auth_<app>` + `Authorization $bff_authorization` 을 쓰고, **`X-Doro-Notify-App <app>` 을 location 이 고정**한다. 기존 `/<app>/api/` 보다 접두사가 길어 먼저 맞는다. notify 는 BFF 서비스가 아니라 `doro.bff.services` 에 없다. 설계: `NOTIFICATION_SERVICE_DESIGN.md` 7.1 |
| `/blog/` | `blog_web_upstream/` | 블로그 웹. `/blog` 를 떼고 전달(웹은 `VITE_BASE_PATH=/blog` 로 빌드돼 에셋이 `/blog/assets/`). `= /blog` 는 `/blog/` 로 302 |
| `/assets/` | **`$assets_upstream`**(기본 포털, §3) | 포털 빌드 산출물(블로그 에셋은 `/blog/assets/` 로 와서 이 규칙을 타지 않는다) |
| `/menu/` | `menu_upstream/` | 도로메뉴(`/menu` 접두어 제거) |
| `/doro/` | `menu_upstream/doro/` | 도로메뉴 정적 자산 |
| `= /` | `portal_upstream` | **메인 주소: Doro 허브**(서비스 카드 + 계정) |
| `= /robots.txt` | `portal_upstream` | 크롤러 규칙(루트에서만 읽힘). `/blog/` 의 비공개 영역 Disallow 포함 |
| `~ ^/(doro-logo\.png\|favicon\.png)$` | `blog_web_upstream` | 이미 공유된 글의 `og:image` 가 가리키는 루트 파일 |
| `~ ^/(sw\.js\|registerSW\.js\|workbox-….js\|manifest\.webmanifest\|icon-192.png\|icon-512.png\|apple-touch-icon.png\|favicon.svg\|index.html\|services.json)$` | `portal_upstream` | **허브 PWA 파일과 `/services.json`.** `services.json` 은 각 서비스 헤더의 서비스 목록 버튼이 읽는 서비스 목록(허브가 빌드 때 `PUBLIC_SERVICES` 로 만든다, precache 대상 아님)이다. 빠지면 `/blog/services.json` 으로 301 된다. 서비스 워커 범위가 `/` 라서 루트에 둔다. `/index.html` 은 서비스 워커가 앱 셸로 미리 캐시하는 주소라 반드시 포함해야 한다(빠지면 `/blog` 이동에 걸려 블로그 화면이 허브 셸로 저장된다). 새 PWA 파일 이름이 생기면 이 정규식에 추가한다. 배포 전·후에 `scripts/check-sw-precache.sh` 가 `sw.js` 의 미리 캐시 목록 전체가 게이트웨이에서 이동 없이 나가는지 자동 점검한다(허브 `deploy.yml`, 블로그·메뉴 `deploy-on-server.sh`) |
| `/` | 301 → `/blog$request_uri` | **옛 블로그 주소**(`/@사용자/글`, `/tags` …)를 `/blog/` 아래로 영구 이동한다(쿼리 유지). 301 은 브라우저가 기억하므로 되돌리기 어렵다 |

### 2.3 정규식 (프리픽스보다 우선)

| location | 대상 | 비고 |
|---|---|---|
| `~ ^/api/v1/sessions(/\|$)` | `iam_upstream` | 세션 관리, `sessions/current`(SDK 폐기 확인). 경계(`/` 또는 끝)까지 맞춰 `/api/v1/sessionsX` 같은 경로는 걸리지 않는다 |
| `~ ^/api/v1/admin/users(/\|$)` | `iam_upstream` | 사용자 관리(목록, 역할 변경, 2FA 초기화, 정지·해제, 잠금 해제). 같은 경계 규칙 |
| `~ ^/(logs\|login\|signup\|account\|portal)(/.*)?$` | `portal_upstream` | 포털 SPA 라우트. 포털 SPA 의 실제 라우트는 `/login`, `/signup`, `/account`, `/logs`(+ 정확 일치 `/oauth2/consent`)이다. `/portal` 도 이 정규식에 걸려 포털로 가며 허브(`/`)와 같은 화면이다 |

위 목록에 없는 경로(예: `/swagger-ui.html`, `/v3/api-docs`, `/actuator/**`, `/health`)는 **IAM 으로 가지 않고** `/` 로 떨어져 `/blog` 아래로 301 이동한다(블로그 프런트엔드가 받는다). 의도된 것이다 — 이 경로들을 게이트웨이로 노출하려면 별도 검토(인증/보안)가 필요하다.

---

### 2.4 BFF 라우팅 (`doro-bff`, [`BFF.md`](BFF.md))

블로그(`/blog/api/…`, 옛 주소 `/api/v1/…`), 도로 파티(`/party/api/…`), 도로 게임 천국(`/games/api/…`)에 같은 규칙이 적용된다.

- **로그인·콜백·로그아웃**: `/{서비스}/api/v1/bff/login|callback|logout` 는 정확 일치 location 이 `bff_upstream` 의 `/bff/{서비스}/login|callback|logout` 으로 보낸다. 블로그는 옛 루트 주소(`/api/v1/bff/…`)도 같은 곳으로 이어 준다. 로그인 시작은 IP 당 속도 제한(`blog_login`)이 있다.
- **`auth_request` 내부 location**: `/_bff_auth_blog`, `/_bff_auth_party`, `/_bff_auth_games`(모두 `internal`, 밖에서 부르면 404). 서비스 쿠키가 있을 때만 `bff_upstream` 의 `/internal/auth` 를 부르고, 쿠키가 없으면 BFF 에 닿지 않고 `204` 로 통과한다(공개 요청·API 키 요청은 BFF 가 내려가 있어도 영향이 없다).
- **`$bff_authorization` 규칙**: 서비스 API location 은 `auth_request_set $bff_token $upstream_http_x_doro_access_token` 으로 BFF 가 돌려준 토큰을 받아 `Authorization` 을 정한다. 쿠키가 있어 토큰이 오면 `Bearer <토큰>` 으로 **덮어쓰고**(클라이언트가 보낸 값은 버린다), 토큰이 없으면(쿠키 없음·익명) 클라이언트가 보낸 `Authorization`/`X-API-Key` 를 그대로 통과시킨다.
- **JSON 오류 응답**: 쿠키로 상태를 바꾸는 요청에 CSRF 증명이 없어 BFF 가 403 을 주면 `@bff_forbidden` 이 서비스에 닿기 전에 JSON `403`(`AUTH-403-03`)으로 끝낸다. 쿠키가 있는데 BFF 에 닿지 못하면 `@bff_unavailable` 이 익명으로 넘기지 않고 JSON `503`(`AUTH-503-02`, `Retry-After: 5`)을 준다.
- 새 서비스를 BFF 에 붙일 때는 같은 구성(로그인·콜백·로그아웃 location + `/_bff_auth_<서비스>` + 서비스 API location)을 추가하고 `scripts/gateway-routing/expect-current.txt` 에 기대 라우팅을 더한다. BFF 준비는 `scripts/enable-bff.sh`, 구조는 `docs/BFF.md` 를 본다.

---

## 3. referer 기반 맵 (두 가지)

한 경로를 두 서비스가 공유하기 때문에 `Referer` 로 갈린다. **브라우저가 보내는 `Referer` 에 의존하는 규칙이므로 취약하다.**

### 3.1 `/assets/` → `$assets_upstream`
```
default                                              → portal_upstream
```
블로그가 `/blog/` 아래로 옮겨 에셋이 `/blog/assets/` 로 오므로 더 이상 `Referer` 로 추측하지 않는다(이전에는 포털/블로그가 같은 `/assets/` 를 나눠 써서 URL 에 `login` 같은 단어가 있으면 깨졌다).

### 3.2 `= /api/v1/users/me` → `$users_me_upstream`
키는 `"$http_referer:$request_method"` 이지만 지금은 규칙이 기본값 하나뿐이다.
```
default                                              → iam_upstream
```
루트의 `/api/v1/users/me` 는 IAM(포털)의 것이다. 블로그는 `/blog/api/v1/users/me` 를 쓴다.

---

## 4. 헤더·보안 규칙 (443 서버)

### 4.1 클라이언트 IP 헤더는 **덮어쓴다**
모든 프록시 location 이 `X-Real-IP $remote_addr`, `X-Forwarded-For $remote_addr`(이어붙이는 `$proxy_add_x_forwarded_for` **아님**), `X-Forwarded-Proto $scheme`, `Host $host` 를 설정한다. 클라이언트가 보낸 `X-Real-IP`/`X-Forwarded-For` 는 신뢰하지 않는다. IAM 은 신뢰 프록시(기본 루프백+도커 브리지)에서 온 연결의 `X-Real-IP` 만 클라이언트 IP 로 쓴다(요청 제한·세션 기록·로그). 새 location 에도 같은 헤더 4개를 반드시 넣는다. (`/shield.svg` 는 `Host` 만 넣는 정적 예외.)

### 4.2 공통 보안 헤더 (server 수준, `always`)
`X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`, `X-XSS-Protection: 0`, `Strict-Transport-Security: max-age=31536000; includeSubDomains`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy`, 그리고 **`Content-Security-Policy`(강제 모드, Report-Only 아님)**. 위반은 `report-uri /csp-report` 로 계속 수집된다.
- CSP 는 `default-src 'self'` 기반이며, 인라인 GA4 초기화 스크립트(세 앱 공통)는 `script-src` **해시 하나**로 허용한다. `index.html` 의 인라인 스크립트를 바꾸면 해시도 바꿔야 한다 — Doro 저장소의 `scripts/check-csp-hash.sh`(Doro 배포가 포털 `web/index.html` 을 서버의 실제 게이트웨이 설정에 대해 검사한다. blog/menu 는 수동 인자로 검사).
- 새 외부 출처(폰트/스크립트/API/이미지)를 쓰면 CSP 에 추가해야 한다. 문제가 생기면 임시로 헤더 이름을 `Content-Security-Policy-Report-Only` 로 되돌릴 수 있다고 설정 주석에 적혀 있다.
- **nginx 는 `location` 안에 `add_header` 가 하나라도 있으면 server 수준 `add_header` 를 상속하지 않는다.** location 에 헤더를 추가하려면 공통 헤더를 다시 적어야 한다(`/media/` 가 그렇게 한다).

### 4.3 `/media/` — 업로드 파일 격리
업스트림(MinIO)이 붙이는 `X-Content-Type-Options`, `X-XSS-Protection`, `Strict-Transport-Security`, `Content-Security-Policy` 는 `proxy_hide_header` 로 숨기고, 공통 헤더를 다시 붙이되 CSP 는 **`default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; sandbox`** 로 덮어쓴다. 업로드된 파일(특히 SVG)이 문서로 직접 열려도 스크립트/외부 로딩이 실행되지 않게 하는 이중 방어다.

### 4.4 기타
- `client_max_body_size 30M`(전역). `/csp-report` 만 4k.
- gzip 은 text/json/js/xml/svg 에 켜져 있다.
- `/loki/` 는 `auth_request` 결과가 401/403 이면 그대로 거부, IAM 에 닿지 못해도 요청은 거부된다(500). IAM 의 `GET /api/v1/admin/authz` 는 JWT 역할(ADMIN 이상) + Guard `system:doro#admin` 을 모두 요구한다.
- 업스트림 이름에 밑줄(`iam_upstream` 등)이 있으므로 `Host` 를 **반드시 `$host` 로 전달**해야 한다(밑줄 포함 Host 는 Tomcat 이 400 으로 거부). 새 location 에서도 `proxy_set_header Host $host;` 를 빼지 말 것.

---

## 5. 게이트웨이로 노출되지 않는 것 (의도된 것)

- **Guard**(REST 8081, gRPC 9090): 프록시하지 않는다. 서비스 인증 기본이 `OFF` 이고 TLS 가 없으므로 외부에 노출하면 안 된다. `/api/v1/guard/**` 는 게이트웨이에 location 이 없어 **블로그 API(`/api/v1/`)로 흘러가므로**, Guard API 가 게이트웨이로 열린다고 오해하지 말 것.
- **IAM 내부 API `/internal/**`**: 게이트웨이에 location 이 없고 IAM 도 게이트웨이를 거친 요청(`X-Forwarded-For`/`X-Real-IP` 가 붙은 요청)에는 404 로 답한다. 서비스는 Docker 내부 네트워크에서 직접 부른다.
- IAM 의 `/swagger-ui.html`, `/v3/api-docs`, `/actuator/**`, `/health`, IAM `GET /api/v1/admin/authz`(내부 `auth_request` 전용).
- 데이터 저장소(PostgreSQL, Redis, Guard)는 compose 에서 기본 `127.0.0.1` 바인딩이다. 포털(3000)과 auth(8080)의 호스트 포트는 compose 기본이 `0.0.0.0` 이므로(`WEB_BIND`/`AUTH_BIND`) 운영에서는 `.env` 로 `127.0.0.1` 로 제한하고 외부에는 80/443 만 연다(서버 실제 값은 `[미검증]`).

---

## 6. 새 서비스(또는 새 API 경로) 추가 규칙

1. **upstream 추가**: `http {}` 안에 `upstream {name}_upstream { server <docker 컨테이너/서비스 이름>:<포트>; }`. docker 네트워크 이름으로 접속하므로 호스트 포트는 필요 없다. 게이트웨이 컨테이너가 그 서비스와 **같은 docker 네트워크**에 있어야 한다.
2. **location 추가**(443 서버 블록): 서비스 전용 경로 접두사를 정해 `proxy_pass` 하고 **§4.1 의 헤더 4개**(`Host $host`, `X-Real-IP $remote_addr`, `X-Forwarded-For $remote_addr`, `X-Forwarded-Proto $scheme`)를 넣는다. 서비스를 하위 경로(`/menu/` 처럼)에 올리면 `proxy_pass http://upstream/;`(끝 슬래시)로 접두어를 떼는지 유지하는지 의도를 분명히 한다.
3. **우선순위 함정**
   - **광범위한 프리픽스(`/api/v1/`, `/`) 보다 구체적인 경로를 쓰라.** 새 API 가 `/api/v1/` 아래에 있으면 기본으로 블로그 백엔드에 걸린다. IAM 계열처럼 다른 서비스로 보내려면 `/api/v1/<새경로>` 프리픽스(또는 정확 일치)를 **추가**해야 한다(파일 내 위치는 상관없다 — 프리픽스는 길이로 결정).
   - **IAM 경로는 경계까지 맞춘다**: `/api/v1/auth/`(끝 슬래시 포함)와 `^/api/v1/sessions(/|$)`, `^/api/v1/admin/users(/|$)` 정규식은 이름이 같은 경로만 잡고 `/api/v1/authors` 같은 비슷한 이름은 `/api/v1/` 로 간다. 새 IAM 경로를 추가할 때도 같은 방식으로 경계를 포함한다(예: `/api/v1/admin/oauth/`).
   - **정규식 location 은 프리픽스보다 우선**한다. 포털 정규식이 `/logs`, `/login`, `/signup`, `/account`, `/portal` 로 시작하는 **모든** 경로를 가져가므로, 새 서비스 경로가 이 이름으로 시작하면 의도와 상관없이 포털로 간다. 새 정규식을 추가하면 파일 순서가 결정하므로 기존 규칙과 겹치지 않는지 확인한다.
   - **정확 일치(`=`)는 항상 최우선**이다. 한 경로만 다른 곳으로 보내야 하면 `= /경로` 로 선언한다(`= /oauth2/consent` 가 그 예).
   - **referer 맵은 새 화면에서 깨지기 쉽다**(§3). 가능하면 referer 에 의존하지 말고 경로를 분리한다. 새 포털 화면 이름을 맵의 정규식에 추가해야 하는 경우도 있다(`/assets/` 맵은 단어가 referer 어디든 있으면 맞고, `users/me` 맵은 referer 끝에서만 맞는다).
   - 서버 `rewrite` 가 `/api/v1/...` 끝 슬래시를 제거하므로, 끝 슬래시가 의미 있는 API 경로는 영향을 받는다.
4. **인증이 필요한 내부/관리자 경로**는 `/loki/` 처럼 `auth_request /_auth_admin;` 로 보호한다. 클라이언트가 보낸 인증 헤더가 그대로 IAM 으로 전달되는 점과, 쓰기 메서드를 `limit_except` 로 막을지 함께 결정한다.
5. **CSP·헤더**: 새 서비스가 외부 출처(폰트, 스크립트, API, 이미지)를 쓰면 server 수준 CSP 를 수정한다. location 에 `add_header` 를 넣으면 공통 보안 헤더를 다시 적어야 한다(§4.2).
6. **Guard 를 프록시하지 말 것.**
7. **검증**(워크스페이스 룰 §5): 설정 반영 전 `nginx -t`, 반영 후 공개 도메인으로 실제 호출해 의도한 서비스로 가는지 확인한다(404/502/500, 엉뚱한 서비스 응답 여부). 예: `curl -sS -o /dev/null -w '%{http_code}\n' https://<공개 도메인>/<새 경로>`, 헤더 확인은 `curl -sSI`. referer 맵에 걸린 경로는 `-H 'Referer: https://<공개 도메인>/account'` 로도 시험한다.

---

## 7. 포털 컨테이너의 nginx (`web/nginx.conf`)

게이트웨이 뒤의 포털 컨테이너(`doro-web-portal`, 호스트 3000 → 컨테이너 80)가 쓰는 설정이다. 게이트웨이 설정과 혼동하지 말 것(CI 가 이미지로 배포하는 쪽은 이 파일이다).

| location | 동작 |
|---|---|
| `/` | 정적 `index.html`, `try_files $uri $uri/ /index.html` (SPA 폴백) |
| `/api/` | → `auth-api:8080/api/` (IAM 전체) |
| `= /oauth2/consent` | 프록시하지 않고 `index.html` 반환(SPA 라우트). 아래 `/oauth2/` 보다 우선 |
| `/oauth2/` | → `auth-api:8080/oauth2/` |
| `/.well-known/` | → `auth-api:8080/.well-known/` |
| `= /50x.html` | 오류 페이지 |

로그 조회(`/loki/`)와 그 관리자 인가 확인(`/_auth_admin`)은 포털 컨테이너가 아니라 **공개 게이트웨이**가 처리한다(중앙 Loki 로 전달). 포털 nginx 에는 이 location 이 없다.

- 포털은 `BrowserRouter` 에 **`basename` 을 쓰지 않는다**(루트 기준 라우트). 과거 문서의 `/portal` 하위 경로 마운트·`basename="/portal"` 규칙은 현재 코드에 해당하지 않는다. Vite `base` 도 기본값이다.
- 포털 컨테이너의 `/api/` 는 IAM 전체를 프록시하지만 **공개 게이트웨이는 그렇지 않다**(§2·§5의 경로 목록이 기준). 로컬에서 포털(3000)로 되는 호출이 게이트웨이에서는 다른 곳으로 갈 수 있으니 게이트웨이 기준으로 확인한다.
- 포털 개발 서버(`npm run dev`, 3000)는 `/api`·`/oauth2`·`/.well-known` 을 `localhost:8080` 으로 프록시한다(`web/vite.config.ts`).

---

## 8. 배포·운영 — **게이트웨이는 CI 로 배포되지 않는다**

- `.github/workflows/deploy.yml` 은 compose 서비스(postgres, redis, auth-api, guard-api, web)만 빌드·배포한다. **`gateway/nginx.conf` 를 바꿔 커밋·푸시해도 서버의 게이트웨이에는 반영되지 않는다.** 블로그/메뉴도 이 워크플로 밖이다.
- 반영 절차(수동):
  1. 서버의 **현재 게이트웨이 설정을 백업**한다(실패 시 즉시 되돌릴 사본을 반드시 남긴다).
  2. 저장소의 `gateway/nginx.conf` 를 서버의 게이트웨이 설정 위치에 복사한다.
  3. **`nginx -t`**(게이트웨이 컨테이너 안에서)로 문법·참조 검사. 실패하면 반영하지 않고 백업으로 복구한다.
  4. **무중단 reload**(`nginx -s reload`, 컨테이너 안에서). 컨테이너를 재시작하지 않는다.
  5. §6-7 의 실제 호출 검증. 문제가 있으면 백업 설정으로 되돌리고 다시 reload.
- 게이트웨이 컨테이너 이름·실행 방식은 compose 에 정의돼 있지 않아 이 저장소로는 확인할 수 없다. `[미검증]` — 서버에서 실행 중인 컨테이너를 직접 확인하고 그 이름으로 `nginx -t`/`nginx -s reload` 를 실행한다. (과거 문서는 `doro-gateway` 라는 이름을 썼다.)
- 설정을 바꾼 커밋과 서버 반영은 별개이므로, 두 쪽이 어긋나지 않게(저장소 = 서버) 반영 후 서버 사본과 저장소 사본의 차이를 확인한다.
- 인증서 갱신은 80 의 `/.well-known/acme-challenge/`(`/var/www/certbot`)를 통한다. 이 location 을 지우지 말 것.

---

## 9. 백엔드 API 호환성·예외 처리 원칙 (유지)

1. **리소스 수정 메서드**: 클라이언트(Axios 등)에 따라 `PUT`/`PATCH` 가 섞일 수 있으면 둘 다 받도록 매핑한다. 단 IAM 의 `/api/v1/users/me` 는 현재 **`PATCH`**, `/api/v1/users/me/password` 는 **`PUT`** 이다(게이트웨이의 `users/me` 맵이 `PATCH` 를 IAM 으로 보내는 것도 이 때문).
2. **`GlobalExceptionHandler` 표준화**: `HttpRequestMethodNotSupportedException` 은 **405**, `HttpMessageNotReadableException`(본문 누락/JSON 오류)은 **400** 으로 처리하고 500 으로 응답하지 않는다. (IAM·Guard 모두 이 규칙을 따른다.)
3. **인앱 액션 우선**: 외부 포털 링크로 사용자를 이탈시키기보다 블로그 안에서 즉시 처리할 수 있는 API(DORO ID 인앱 회원가입/로그인 등)를 기본 제공한다.
