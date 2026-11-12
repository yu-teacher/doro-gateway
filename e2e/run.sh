#!/usr/bin/env bash
# 통합(E2E) 시험 실행: 격리 스택(진짜 IAM·Guard·Postgres·Redis·S3 목) + 포털 개발 서버 + 블로그 백엔드를 띄우고,
# 진짜 Chromium 으로 "도로로 로그인" 흐름을 끝까지 시험한 뒤 모두 내린다. 개발·운영 스택과 포트·이름이 겹치지 않는다.
#
#   e2e/run.sh            # 전체 실행(처음엔 이미지 빌드로 몇 분 걸린다)
#   KEEP=1 e2e/run.sh     # 시험 후에도 스택을 남겨 둔다(디버깅용; 직접 내릴 때: e2e/run.sh --down)
#
# 필요: Docker, Java 25, Node 24, 형제 디렉터리 ../Doro(IAM·Guard·포털, E2E_DORO_DIR 로 변경)와 ../doro-blog. 시험 계정 비밀번호는 실행마다 무작위이며 .state/ 에만 있다.
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
MODE_OVERRIDE="${E2E_BFF_MODE:-}"
set -a; source e2e.env; set +a
[ -z "$MODE_OVERRIDE" ] || E2E_BFF_MODE="$MODE_OVERRIDE"
# 서비스 안의 BFF(embedded)는 2026-11 에 지웠다. 별도 BFF + nginx(gateway) 만 있다.
case "$E2E_BFF_MODE" in gateway) ;; *) echo "E2E_BFF_MODE 는 gateway 여야 한다(embedded 는 없어졌다): $E2E_BFF_MODE" >&2; exit 1;; esac
export E2E_BFF_MODE
# gateway 모드에서만 BFF 와 nginx 컨테이너를 쓴다(compose 프로필)
COMPOSE=(docker compose -p doro-e2e --env-file e2e.env --profile gateway -f compose.e2e.yml)
BLOG_DIR="${BLOG_DIR:-../../doro-blog}"
# IAM·Guard·포털·DB 초기화 스크립트는 Doro 저장소에서 가져온다(compose.e2e.yml 도 같은 변수를 쓴다)
export E2E_DORO_DIR="${E2E_DORO_DIR:-../../Doro}"
WEB_DIR="$E2E_DORO_DIR/web"
STATE=.state

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

stop_port() { local pids; pids="$(lsof -ti ":$1" 2>/dev/null || true)"; [ -z "$pids" ] || kill $pids 2>/dev/null || true; }
down() {
  stop_port "$E2E_PORTAL_PORT"; stop_port "$E2E_BLOG_PORT"
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}
if [ "${1:-}" = "--down" ]; then down; log "내렸다"; exit 0; fi

mkdir -p "$STATE"; chmod 700 "$STATE"
[ -d "$BLOG_DIR" ] || die "$BLOG_DIR 가 없다(BLOG_DIR 로 경로 지정)"
[ -d "$E2E_DORO_DIR/auth" ] || die "$E2E_DORO_DIR 가 없다(E2E_DORO_DIR 로 경로 지정)"
for p in "$E2E_AUTH_PORT" "$E2E_PORTAL_PORT" "$E2E_BLOG_PORT" "$E2E_PG_PORT" "$E2E_GATEWAY_PORT" "$E2E_BFF_PORT"; do
  ! lsof -ti ":$p" >/dev/null 2>&1 || die "포트 $p 가 이미 쓰이고 있다(e2e/e2e.env 에서 바꾼다)"
done
trap '[ "${KEEP:-0}" = 1 ] || { log "정리"; down; }' EXIT

wait_http() { # url, seconds, label
  local deadline=$((SECONDS + $2))
  until curl -fsS -o /dev/null "$1" 2>/dev/null; do [ $SECONDS -lt $deadline ] || die "$3 가 $2 초 안에 준비되지 않았다"; sleep 3; done
}

log "== 1/5 격리 스택 기동 (IAM · Guard · Postgres · Redis · S3, 모드: $E2E_BFF_MODE) =="
if [ "$E2E_BFF_MODE" = gateway ]; then
  # BFF 이미지는 jar 를 담기만 한다. 이 저장소의 지금 코드로 jar 를 먼저 만든다.
  (cd .. && ./gradlew bootJar -x test --console=plain -q)
  "${COMPOSE[@]}" up -d --build
else
  "${COMPOSE[@]}" up -d --build postgres redis guard-api auth-api s3
fi
wait_http "http://localhost:${E2E_AUTH_PORT}/actuator/health" 300 "IAM(auth)"
wait_http "http://localhost:${E2E_GUARD_HTTP_PORT}/actuator/health" 120 "Guard"
[ "$E2E_BFF_MODE" != gateway ] || wait_http "http://localhost:${E2E_BFF_PORT}/actuator/health" 180 "BFF"

log "== 2/5 포털 개발 서버 =="
[ -d "$WEB_DIR/node_modules" ] || (cd "$WEB_DIR" && npm ci --no-audit --no-fund)
(cd "$WEB_DIR" && VITE_DEV_API_TARGET="http://localhost:${E2E_AUTH_PORT}" nohup npx vite --port "$E2E_PORTAL_PORT" --strictPort > "$OLDPWD/$STATE/portal.log" 2>&1 &)
wait_http "http://localhost:${E2E_PORTAL_PORT}/" 90 "포털"

log "== 3/5 계정·OAuth 클라이언트 준비 =="
[ -d node_modules ] || npm install --no-audit --no-fund >/dev/null
node setup.mjs

log "== 4/5 블로그 백엔드 =="
SESSION_KEY="$(openssl rand -base64 32)"
(cd "$BLOG_DIR" && env \
  SERVER_PORT="$E2E_BLOG_PORT" \
  DB_HOST=localhost DB_PORT="$E2E_PG_PORT" DB_NAME=service_blog DB_USER=doro_admin DB_PASSWORD="$E2E_DB_PASSWORD" \
  IAM_HOST=localhost IAM_PORT="$E2E_AUTH_PORT" \
  GUARD_GRPC_HOST=localhost GUARD_GRPC_PORT="$E2E_GUARD_GRPC_PORT" GUARD_HTTP_HOST=localhost GUARD_HTTP_PORT="$E2E_GUARD_HTTP_PORT" \
  MINIO_ENDPOINT="http://localhost:${E2E_S3_PORT}" MINIO_ACCESS_KEY=e2e MINIO_SECRET_KEY=e2e \
  BLOG_OAUTH_CLIENT_ID=doro-blog \
  BLOG_OAUTH_AUTHORIZE_URL="http://localhost:${E2E_PORTAL_PORT}/oauth2/authorize" \
  BLOG_OAUTH_REDIRECT_URI="http://localhost:${E2E_BLOG_PORT}/api/v1/bff/callback" \
  BLOG_COOKIE_SECURE=false BLOG_SESSION_KEY="$SESSION_KEY" BLOG_AUTH_REFRESH_MARGIN=PT5S \
  BLOG_DELETED_SYNC_ENABLED=false BLOG_AUTH_MODE="$E2E_BFF_MODE" \
  DORO_IAM_INTERNAL_TOKEN="$E2E_INTERNAL_TOKEN" \
  BLOG_API_KEY_ACCOUNT_STATUS_ENABLED=true BLOG_API_KEY_ACCOUNT_STATUS_CACHE_TTL=PT2S BLOG_API_KEY_ACCOUNT_STATUS_INACTIVE_CACHE_TTL=PT1S \
  nohup ./gradlew bootRun --console=plain > "$OLDPWD/$STATE/blog.log" 2>&1 &)
unset SESSION_KEY
wait_http "http://localhost:${E2E_BLOG_PORT}/api/v1/bff/session" 300 "블로그 백엔드"
[ "$E2E_BFF_MODE" != gateway ] || wait_http "http://localhost:${E2E_GATEWAY_PORT}/api/v1/bff/session" 60 "게이트웨이(nginx)"

log "== 5/5 브라우저 시험 =="
set +e
npx playwright test ${PW_ARGS:-}
rc=$?
set -e
[ $rc -eq 0 ] && log "통과" || log "실패(exit $rc). 로그: e2e/$STATE/*.log"
exit $rc
