# BFF·게이트웨이 통합(E2E) 시험

가짜 IAM 이 아니라 **진짜 IAM(auth)·Guard·Postgres·Redis, 진짜 서비스(블로그 백엔드), 진짜 Chromium** 으로 "도로로 로그인" 흐름을 끝까지 시험한다.
단위·통합 시험(가짜 IAM)이 못 보는 것: 클라이언트 등록, redirect_uri 정확 일치, PKCE, `cid` 클레임, 쿠키 Path, 토큰 갱신, IAM 장애 때의 동작.

```bash
e2e/run.sh            # 격리 스택을 띄우고 시험한 뒤 모두 내린다(처음엔 이미지 빌드로 몇 분)
KEEP=1 e2e/run.sh     # 시험 후에도 스택을 남긴다(디버깅). 내릴 때: e2e/run.sh --down
```

필요: Docker, Java 25, Node 24, 형제 디렉터리 `../Doro`(IAM·Guard·포털을 그 소스로 띄운다, `E2E_DORO_DIR` 로 변경)와 `../doro-blog`(`BLOG_DIR` 로 변경).
BFF 는 이 저장소의 지금 코드로 jar 를 만들어 컨테이너로 띄운다.

## 두 가지 모드
- **gateway(기본)**: 브라우저는 nginx(게이트웨이)로 접속하고, 로그인·세션·토큰 갱신은 별도 `doro-bff` 컨테이너가 맡는다. nginx 가 쿠키를 `Authorization: Bearer` 로 바꿔 블로그 백엔드에 넘긴다(블로그는 `BLOG_AUTH_MODE=gateway`). 운영 게이트웨이의 `auth_request` 규칙을 그대로 옮긴 설정은 `gateway/default.conf.template`.

## 구성
- `compose.e2e.yml`: 격리 스택(IAM·Guard 는 `../Doro` 에서 빌드)(컨테이너 이름 `e2e-*`, 포트 5xxxx, 볼륨 없음). 개발·운영 스택과 겹치지 않는다. 설정값은 `e2e.env`(비밀 아님).
- 포털은 `vite` 개발 서버를 `VITE_DEV_API_TARGET` 로 이 IAM 에 붙여 띄운다(로그인 화면).
- `setup.mjs`: 부트스트랩 API 로 첫 최고 관리자 → OAuth 클라이언트(`doro-blog`) 등록 → 시험용 일반 계정. **비밀번호는 실행마다 무작위**이며 `.state/e2e.json`(권한 600)에만 있다.
- 블로그 백엔드는 `gradlew bootRun` 으로 같은 스택에 붙여 띄운다(액세스 토큰 수명 20초로 만료를 빨리 재현).

## 시나리오 (`tests/blog-bff.spec.ts`)
1. 로그인 → 세션 쿠키(HttpOnly) → 복귀 경로 → 새로고침 유지
2. 액세스 토큰이 만료돼도 진짜 IAM 에서 갱신해 같은 쿠키로 유지
3. **IAM 이 죽은 사이 토큰이 만료돼도 로그아웃되지 않고, 복구되면 같은 쿠키로 로그인 상태**
4. 로그인 CSRF: 다른 브라우저의 콜백은 거부
5. 실패한 콜백이 기존 세션을 지우지 않음
6. CSRF 헤더 없는 쿠키 요청 거부, 로그아웃
7. 로그아웃이 IAM 세션까지 폐기(IAM DB 직접 확인)
8. 서비스별 기기 3개(넘으면 가장 오래 안 쓴 기기만 로그아웃)
9. 계정이 정지되면 API 키가 막히고, 해제되면 같은 키가 다시 통한다
10. **(gateway 전용)** 서비스는 쿠키를 모른다: 같은 쿠키를 백엔드에 직접 보내면 익명
11. **(gateway 전용)** 쿠키가 있으면 클라이언트가 보낸 `Authorization` 은 BFF 토큰으로 덮어쓰인다
12. **(gateway 전용)** 쿠키로 상태를 바꾸는 서비스 API 도 CSRF 헤더가 없으면 게이트웨이에서 403
13. **(gateway 전용)** BFF 장애: 쿠키 없는 공개 요청은 정상, 쿠키 있는 요청만 503, 복구되면 같은 쿠키로 로그인 상태

## 알아 둘 점
- 로컬 개발 환경 전용이다. 운영 서버에는 접속하지 않는다.
- 포털 개발 서버의 프록시는 `/oauth2/consent` 를 IAM 이 아니라 포털 화면으로 처리한다(`web/vite.config.ts`, 운영 게이트웨이와 같은 규칙).
- CI 에 넣지는 않았다(이미지 빌드와 여러 프로세스가 필요해 느리다). BFF·게이트웨이 규칙이나 Doro SDK 를 바꾸기 전후에 손으로 돌린다.
- Doro 저장소의 e2e 에는 BFF 가 없는 시험(외부 OIDC 앱, 영구 탈퇴 동기화)이 남아 있다.

