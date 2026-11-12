# 작업 안내 (AI·사람 공통)

- 범위: BFF(`src/`)와 게이트웨이 설정(`gateway/nginx.conf`). 인증·인가 서버(IAM·Guard)와 SDK 는 Doro 저장소에서 고친다.
- SDK 버전은 `.doro-sdk-ref`(Doro 저장소의 태그). 올릴 때는 그 파일만 바꾸고 CI 로 확인한다.
- 게이트웨이 설정을 바꾸면 `scripts/gateway-routing/expect-current.txt` 도 함께 고치고 `scripts/test-gateway-routing.sh` 를 통과시킨다. 서버 반영은 사람이 한다(`scripts/apply-gateway-conf.sh`, 서버 사본을 통째로 덮어쓰지 않는다).
- BFF 규칙(`docs/BFF.md` 의 "동작 규칙")은 시험으로 고정돼 있다. 규칙을 바꾸면 시험과 문서를 같이 바꾼다.
- 서버 `.env`, 실제 키·토큰은 저장소에 넣지 않는다. 공개 저장소이므로 서버 주소·운영 세부도 넣지 않는다.
