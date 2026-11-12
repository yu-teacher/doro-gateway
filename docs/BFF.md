# doro-bff — 로그인·세션·토큰을 서비스 밖에서 맡는 BFF

> 이 문서는 2026-11 에 Doro 저장소에서 이 저장소(doro-gateway)로 옮겼다. 인증·인가(IAM·Guard·SDK)는 Doro 저장소에 있다.

브라우저 로그인(Doro OAuth)과 세션, 토큰 갱신은 **서비스(blog, party, games …)가 아니라 `doro-bff` 한 곳**이 맡는다.
서비스는 세션도 토큰도 모르고, 게이트웨이가 요청마다 붙여 주는 `Authorization: Bearer` 만 검증한다.

```
브라우저 ──쿠키──▶ 게이트웨이(nginx) ──auth_request──▶ doro-bff ──(갱신)──▶ IAM
                        │                                  └─ 세션 저장소(Postgres, 토큰은 AES-GCM 암호화)
                        └──Authorization: Bearer <액세스 토큰>──▶ 서비스 백엔드(blog / party / games)
```

## 왜 서비스 밖인가

- **책임 위치**: 로그인·토큰 보관·갱신은 인증 영역이다. 서비스가 리프레시 토큰과 암호화 키를 갖지 않는다.
- **서비스는 단순해진다**: 서비스에는 OAuth 설정, 세션 테이블, 쿠키 이름, 갱신 경쟁 처리가 없다. 사용자가 누구인지만 안다(이미 지원하는 Bearer 검증 그대로).
- **새 서비스를 붙이는 비용이 설정 몇 줄**이다(BFF 설정 + 게이트웨이 location + OAuth 클라이언트 등록).
- **다중 인스턴스 준비**: 세션과 갱신 직렬화가 한 곳이라 확장할 때 이 서비스 하나만 바꾼다.

## 요청 흐름

1. 브라우저가 `GET /party/api/v1/maps` 를 보낸다(쿠키 `doro_party_session`).
2. nginx 가 내부 location(`/_bff_auth_party`)으로 `auth_request` 를 보내 BFF 에 쿠키를 넘긴다. **쿠키가 없으면 BFF 를 부르지 않는다.**
3. BFF 는 세션을 찾고(필요하면 IAM 에서 토큰 갱신) `204` + `X-Doro-Access-Token` 으로 답한다. 익명이면 토큰 없이 `204`.
4. nginx 가 토큰이 있으면 `Authorization: Bearer <토큰>` 을 붙여 서비스로 넘긴다. 클라이언트가 보낸 `Authorization` 은 쿠키가 있을 때 버린다.
5. 서비스는 평소처럼 Bearer 를 검증한다.

로그인·콜백·로그아웃(`/{서비스}/api/v1/bff/login|callback|logout`)은 게이트웨이가 BFF 의 `/bff/{서비스}/…` 로 이어 준다. 브라우저가 보는 주소와 쿠키 경로는 서비스 안의 BFF 를 쓰던 때와 같다.

## 지키는 규칙 (테스트로 고정)

- **로그인 CSRF 방지**: `/login` 이 그 브라우저에 짧은 수명의 쿠키를 심고, `/callback` 은 그 쿠키가 있어야 끝낸다. 다른 브라우저의 콜백은 코드 교환 없이 거부한다.
- **state 는 1회용**, 한 서비스의 state 는 다른 서비스 콜백에서 쓸 수 없다. **세션 고정 방지**(새 로그인은 옛 세션을 폐기).
- **서비스 격리**: 세션은 서비스별로 저장하고 쿠키 이름이 다르다. 한 서비스의 쿠키 값을 다른 서비스 쿠키로 보내도 통하지 않는다.
- **IAM 장애·429 는 로그아웃이 아니다**: 갱신이 IAM 5xx/429/연결 실패면 세션을 남기고 이번 요청만 익명으로 둔다. IAM 이 400/401 로 명시적으로 거부할 때만 세션을 끝낸다.
- **쿠키 CSRF**: 쿠키로 상태를 바꾸는 요청(POST/PUT/PATCH/DELETE)은 서비스별 커스텀 헤더가 있어야 하고 `Origin` 이 있다면 이 사이트와 같아야 한다. 게이트웨이 단계에서 서비스에 닿기 전에 `403` 으로 끝난다.
- **BFF 가 죽어도 공개 화면은 영향이 없다**: 쿠키 없는 요청은 BFF 를 거치지 않는다. 쿠키가 있는 요청만 `503`(익명으로 넘기지 않는다 — 로그인한 사용자가 갑자기 로그아웃된 것처럼 보이지 않게).
- **헤더 위조 방지**: 쿠키가 있으면 클라이언트가 보낸 `Authorization` 은 BFF 의 토큰으로 덮어쓴다. 쿠키가 없을 때의 `Authorization`/`X-API-Key`(API 키, Bearer 직접 호출)는 그대로 통과한다.
- 복귀 경로 검증(오픈 리다이렉트 방지), 실패한 콜백이 기존 세션 쿠키를 건드리지 않음, 로그아웃이 IAM 세션까지 폐기(RFC 7009).

## 구성

| 구성 요소 | 위치 |
|---|---|
| BFF 로그인·세션·토큰 갱신 로직(서비스 무관) | `src/main/java/.../bff/core` (`BffAuthService`, `BffOAuthClient`, `BffController`, `BffCookies` …) |
| 서비스별 구성·저장소·`auth_request` 엔드포인트 | `src/main/java/.../bff` (`BffServiceRegistry`, `BffGatewayController`, `AuthRequestController`) |
| 게이트웨이 규칙 | `gateway/nginx.conf` 의 "BFF(doro-bff)" 구간 |
| 통합(E2E) 시험 | `e2e/` (진짜 nginx · BFF · IAM · Guard · 서비스 · Chromium) |

설정은 `doro.bff.*` 에 있다. 공통: IAM 주소, 세션 암호화 키(`BFF_SESSION_KEY`, base64 32바이트), 쿠키 정책. 서비스별(`doro.bff.services.<이름>.*`): OAuth `client-id`, 쿠키 이름·경로, 웹 기준 경로, `redirect-uri`, CSRF 헤더, 복귀 경로 규칙. 서비스 이름은 게이트웨이가 `X-Doro-Service` 로 넘긴다. 서비스끼리 클라이언트 ID나 쿠키 이름이 겹치면 기동하지 않는다.

## 서비스 쪽

서비스에는 BFF 코드·세션 저장소가 없고 게이트웨이가 붙여 준 Bearer 만 검증한다. 사용자 행은 로그인 후 첫 `GET /api/v1/bff/session` 조회에서 만든다.
(예전에는 서비스 안에서 BFF 를 돌리는 `embedded` 모드가 있었다. 모든 서비스가 이 구조로 옮긴 뒤 2026-11 에 그 코드를 서비스와 SDK 에서 지웠다.)

## 동작 규칙 (테스트로 고정돼 있다)

- **로그인 CSRF 방지**: `/login` 이 로그인을 시작한 브라우저에 짧은 수명의 쿠키를 심고, `/callback` 은 그 쿠키가 있어야 끝낸다. 다른 브라우저의 콜백은 코드 교환 없이 거부하고 state 도 소진된다.
- **state 는 1회용**: 저장소의 `consume` 이 행을 실제로 지운 요청만 통과한다.
- **세션 고정 방지**: 새 로그인은 이전 세션 쿠키의 세션을 지운다.
- **토큰 갱신**: 액세스 토큰 만료 60초 전부터 갱신하고, 같은 세션의 동시 갱신은 한 번만 IAM 을 부른다(리프레시 토큰은 회전되므로 두 요청이 같은 토큰을 쓰면 IAM 이 탈취로 본다). 저장은 토큰 컬럼만 바꾸는 `updateTokens` 로 한다.
- **IAM 장애·429 는 로그아웃이 아니다**: 갱신이 IAM 5xx, 429, 408, 403, 404, 연결 실패면 세션을 지우지 않고 **이번 요청만 익명**으로 둔다. IAM 이 400/401 로 명시적으로 거부할 때만 세션을 끝낸다.
- **IAM 세션 폐기 반영**: 액세스 토큰 검증이 실패하면(다른 곳에서 로그아웃) 세션을 지운다.
- **쿠키 CSRF**: 쿠키가 있는 요청이 상태를 바꾸면(POST/PUT/PATCH/DELETE) 커스텀 헤더가 있어야 하고, `Origin` 이 있다면 이 사이트와 같아야 한다.
- **복귀 경로(`return`)**: 사이트 안의 올바른 URI 경로만 허용한다(`//`, `://`, 역슬래시, 제어 문자, URI 문법 위반, 500자 초과는 첫 화면). 오픈 리다이렉트와 콜백 500 을 막는다.
- **실패한 콜백은 기존 세션 쿠키를 건드리지 않는다**: 가짜 콜백 링크 하나로 남을 로그아웃시킬 수 없다.
- 토큰 응답 오류 구분: IAM 응답 400/401 만 "거부"이고 나머지는 "일시 장애"다.


## 새 서비스를 붙이는 순서

1. Doro 관리자 API 로 OAuth 클라이언트를 등록한다(`first_party=true`, 콜백 `redirect_uri` 는 서비스의 `/…/api/v1/bff/callback`).
2. `doro.bff.services.<이름>` 에 클라이언트·쿠키·경로·CSRF 헤더를 추가한다(`doro.iam.oauth-client-ids` 에도 클라이언트 ID).
3. 게이트웨이에 서비스의 BFF 라우트(login/callback/logout), `auth_request` 내부 location, API location 의 `auth_request` + `Authorization` 설정을 추가한다.
4. 서비스는 Bearer 검증과 `GET /api/v1/bff/session`(서비스 프로필 모양)만 제공한다.

## 운영

- 컨테이너 `doro-bff-api`(포트 8085, 헬스 `/actuator/health`), DB `doro_bff`(배포 단계가 없으면 만든다).
- 배포: main 에서 CI 가 통과하면 서버의 셀프호스티드 러너가 `scripts/deploy-on-server.sh` 로 jar·이미지를 빌드해 교체하고, 게이트웨이를 reload 한 뒤 게이트웨이 경유 로그인 시작(302)을 확인한다. 실패하면 직전 이미지로 되돌린다.
- 서버 준비: 서버의 `~/doro-gateway/.env`(DB 계정, 세션 키, IAM 내부 토큰, issuer). 처음 옮길 때 `scripts/setup-gateway-server.sh` 가 Doro 의 `.env` 에서 만든다(비밀 값은 출력하지 않는다).
- 세션 암호화 키는 바꾸지 않는다(바꾸면 모든 BFF 세션이 무효가 되어 다시 로그인한다).
- 게이트웨이는 `auth_request` 로 BFF 주소를 시작·reload 때 한 번 해석한다. 배포 파이프라인이 reload 하므로 컨테이너가 교체돼도 따라간다.

## 시험

- 단위·웹: `src/test` — 로그인·콜백·로그아웃·`auth_request`(토큰·익명·CSRF·설정 오류·갱신·IAM 장애), 서비스 격리, 설정 검증, 마이그레이션 스키마 검증. PostgreSQL 에서도 같은 시험을 돌린다(`scripts/ci-test.sh`).
- 게이트웨이 라우팅: `scripts/test-gateway-routing.sh gateway/nginx.conf scripts/gateway-routing/expect-current.txt`.
- 통합: `e2e/run.sh` (진짜 nginx·BFF·IAM·서비스·Chromium 으로 로그인·토큰 만료 갱신·IAM 장애·BFF 장애·CSRF·위조 헤더·3기기 등 13개 시나리오).
- 시험이 실제로 회귀를 잡는지 게이트웨이 설정을 일부러 망가뜨려 확인했다: 클라이언트 `Authorization` 덮어쓰기 제거 → 위조 헤더 시나리오 실패, 서비스 API 의 `auth_request` 제거 → 로그인 상태 시나리오 실패, 쿠키 없는 요청의 BFF 우회 분기 제거 → BFF 장애 시나리오 실패, CSRF 403 처리 제거 → CSRF 시나리오 실패. 원복하면 모두 통과한다.
