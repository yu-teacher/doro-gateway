# doro-gateway

Doro 로 로그인하는 서비스들의 **입구**다. 브라우저 로그인·세션·토큰 갱신을 서비스 대신 맡는 BFF(`doro-bff`)와,
모든 요청이 지나가는 게이트웨이(nginx) 설정이 여기 있다.

인증·인가 자체(IAM, Guard, 토큰 검증 SDK, 로그인·동의 화면)는 [Doro](https://github.com/yu-teacher/doro) 저장소에 있다.
이 저장소는 Doro 의 OAuth 클라이언트 쪽 패턴(BFF)이다. 서비스는 이 입구를 거쳐 `Authorization: Bearer` 만 받고, 세션도 토큰도 모른다.

```
브라우저 ──쿠키──▶ 게이트웨이(nginx) ──auth_request──▶ doro-bff ──(갱신)──▶ Doro IAM
                        └──Authorization: Bearer <액세스 토큰>──▶ 서비스(blog / party / games / interview)
```

## 구성

| 위치 | 내용 |
|---|---|
| `src/` | BFF(Spring Boot). 로그인·콜백·로그아웃, `auth_request` 엔드포인트, 세션 저장소(Postgres, 토큰은 AES-GCM 암호화), 토큰 갱신 |
| `gateway/nginx.conf` | 게이트웨이 설정의 저장소 사본(서버 반영은 사람이 `scripts/apply-gateway-conf.sh` 로 한다) |
| `scripts/test-gateway-routing.sh`, `scripts/gateway-routing/` | 게이트웨이 라우팅 회귀 시험(가짜 업스트림으로 경로별 결과 확인) |
| `e2e/` | 진짜 IAM·BFF·nginx·서비스·Chromium 으로 로그인 흐름 13개 시나리오 |
| `docs/BFF.md` | BFF 설계와 지키는 규칙 |
| `docs/GATEWAY_ROUTING_RULES.md` | 게이트웨이 라우팅 규칙 |

## 빌드와 시험

Doro SDK(토큰 검증)를 소스로 빌드하므로 Doro 저장소가 형제 디렉터리 `../Doro` 에 있어야 한다. 버전은 `.doro-sdk-ref` 의 태그로 고정한다.

```bash
scripts/ci-test.sh                                   # BFF 시험(H2 + 실제 PostgreSQL). Docker, Java 25 필요
scripts/test-gateway-routing.sh gateway/nginx.conf scripts/gateway-routing/expect-current.txt
e2e/run.sh                                           # 통합 시험(../Doro, ../doro-blog 필요)
```

## 배포

main 에서 CI 가 통과하면 서버의 셀프호스티드 러너가 `scripts/deploy-on-server.sh` 를 실행한다
(jar·이미지 빌드 → DB 백업 → 교체 → 헬스체크 → 게이트웨이 reload → 게이트웨이 경유 로그인 시작 확인, 실패하면 직전 이미지로 복구).
서버 설정(`.env`)은 서버에만 있다. 게이트웨이 `nginx.conf` 는 배포하지 않는다.

2026-11 에 Doro 저장소에서 옮겨 왔다(옮기기 전 이력은 Doro 저장소에 있다).
