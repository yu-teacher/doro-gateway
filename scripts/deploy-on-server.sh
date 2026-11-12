#!/usr/bin/env bash
# 서버(mini)의 셀프호스티드 러너가 실행하는 BFF 배포: 컨테이너 안에서 jar 빌드 → 이미지 빌드 → 롤백 지점 + DB 백업 → 교체 →
# 헬스체크 → 게이트웨이 reload(새 컨테이너 주소를 다시 해석) → 게이트웨이 경유 로그인 시작 확인 → 실패하면 직전 상태로 되돌린다.
# 서버에는 Java 21 만 있어서 Java 25 는 Docker 이미지로 가져온다. 서버의 .env 는 읽기만 하고 덮어쓰지 않는다.
#
#   scripts/deploy-on-server.sh <doro-gateway 체크아웃> <Doro 체크아웃>
#   scripts/deploy-on-server.sh --dry-run <...>   # 사전 점검만
#
# 처음 한 번(Doro 저장소에서 옮겨 오는 배포): 떠 있는 doro-bff-api 가 Doro compose 소속이면 멈추고 이름을 바꿔 두었다가
# 새 컨테이너가 정상이면 지우고, 실패하면 이름을 되돌려 다시 띄운다. 그 사이(수십 초) 쿠키가 있는 요청은 503, 쿠키 없는 공개 화면은 영향이 없다.
set -Eeuo pipefail

DRY_RUN=false
if [ "${1:-}" = "--dry-run" ]; then DRY_RUN=true; shift; fi
SRC="$(cd "${1:?doro-gateway 체크아웃 경로가 필요하다}" && pwd)"
DORO_SRC="$(cd "${2:?Doro 체크아웃 경로가 필요하다}" && pwd)"
[ "$(dirname "$SRC")" = "$(dirname "$DORO_SRC")" ] || { echo "두 체크아웃은 같은 부모 디렉터리 아래 형제여야 한다 (settings.gradle 의 includeBuild('../Doro'))" >&2; exit 2; }
[ "$(basename "$DORO_SRC")" = Doro ] || { echo "Doro 체크아웃 폴더 이름은 Doro 여야 한다" >&2; exit 2; }
PARENT="$(dirname "$SRC")"; NAME="$(basename "$SRC")"

REMOTE_DIR="${DEPLOY_DIR:-$HOME/doro-gateway}"
COMPOSE="docker-compose.prod.yml"
PROJECT=doro-gateway
SERVICE=bff-api
CONTAINER=doro-bff-api
IMAGE=doro-bff:latest
JAR_REL="build/libs/doro-bff-0.0.1-SNAPSHOT.jar"
GATEWAY_CONTAINER="${GATEWAY_CONTAINER:-doro-gateway}"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-150}"
JAVA_IMAGE="${CI_JAVA_IMAGE:-gradle:9.5.1-jdk25}"
# 다른 서비스 배포와 Gradle 캐시를 나누어 쓴다: 같은 캐시 폴더를 동시에 쓰면 잠금 대기로 빌드가 실패할 수 있다.
CACHE="${CI_CACHE_DIR:-$HOME/.cache/bff-deploy}"
TS="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="$HOME/logs"; mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/bff-deploy-$TS.log"
exec > >(tee -a "$LOG_FILE") 2>&1
on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ] && [ "${GITHUB_ACTIONS:-}" = true ]; then
    printf '::error title=deploy-on-server.sh failed (exit %s)::%s\n' "$rc" "$(tail -n 25 "$LOG_FILE" | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
  fi
}
trap on_exit EXIT
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
compose() { (cd "$REMOTE_DIR" && docker compose -p "$PROJECT" -f "$COMPOSE" "$@"); }
project_of() { docker inspect "$1" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true; }

log "== 사전 점검 =="
[ -f "$REMOTE_DIR/.env" ] || die "$REMOTE_DIR/.env 가 없다 (scripts/setup-gateway-server.sh --apply 를 먼저 한다)"
[ -x "$HOME/ops/backup.sh" ] || die "$HOME/ops/backup.sh 가 없다 (DB 백업 없이 배포하지 않는다)"
docker info >/dev/null 2>&1 || die "docker 를 사용할 수 없다"
docker inspect "$GATEWAY_CONTAINER" >/dev/null 2>&1 || die "게이트웨이 컨테이너 $GATEWAY_CONTAINER 가 없다"
CURRENT_PROJECT="$(project_of "$CONTAINER")"
MIGRATE=false
if [ -n "$CURRENT_PROJECT" ] && [ "$CURRENT_PROJECT" != "$PROJECT" ]; then MIGRATE=true; fi
log "  commit: $(git -C "$SRC" rev-parse --short HEAD 2>/dev/null || echo '?'), Doro SDK: $(git -C "$DORO_SRC" describe --tags 2>/dev/null || git -C "$DORO_SRC" rev-parse --short HEAD), 대상: $REMOTE_DIR"
log "  지금 $CONTAINER: ${CURRENT_PROJECT:-없음}$([ "$MIGRATE" = true ] && echo " → 이번 배포에서 $PROJECT 로 옮긴다")"
if [ "$DRY_RUN" = true ]; then log "--dry-run: 여기서 종료"; exit 0; fi

log "== 빌드: jar (시험은 CI 에서 통과한 커밋만 여기까지 온다) =="
mkdir -p "$CACHE/gradle"
docker run --rm --cpus "${CI_CPUS:-2}" --memory "${CI_MEMORY:-4g}" --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -e GRADLE_USER_HOME=/cache/gradle -v "$CACHE:/cache" -v "$PARENT:/work" -w "/work/$NAME" "$JAVA_IMAGE" \
  gradle clean bootJar -x test --no-daemon --console=plain
[ -f "$SRC/$JAR_REL" ] || die "$JAR_REL 이 만들어지지 않았다"

log "== 반영 준비: compose·Dockerfile·jar 복사, 이미지 빌드 (.env 는 건드리지 않는다) =="
mkdir -p "$REMOTE_DIR/build/libs"
cp -f "$SRC/$COMPOSE" "$SRC/Dockerfile" "$SRC/.dockerignore" "$REMOTE_DIR/"
cp -f "$SRC/$JAR_REL" "$REMOTE_DIR/$JAR_REL"
OLD_IMAGE="$(docker inspect --format '{{.Image}}' "$CONTAINER" 2>/dev/null || true)"
[ -z "$OLD_IMAGE" ] || docker tag "$OLD_IMAGE" "doro-bff-rollback:$TS"
compose build "$SERVICE" 2>&1 | tail -3

log "== DB 백업 =="
"$HOME/ops/backup.sh" backup | tail -2

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SEC))
  until [ "$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo none)" = healthy ]; do
    [ $SECONDS -lt $deadline ] || return 1
    sleep 3
  done
}
# 게이트웨이는 upstream 이름을 시작·reload 때만 해석한다. 컨테이너가 바뀌면 reload 해야 새 주소로 간다.
reload_gateway() { docker exec "$GATEWAY_CONTAINER" nginx -t >/dev/null 2>&1 && docker exec "$GATEWAY_CONTAINER" nginx -s reload; }
# 게이트웨이 경유 로그인 시작이 IAM 으로 302 이동하는지(BFF 까지 닿는지) 확인한다.
gateway_ok() {
  local code i
  for i in $(seq 1 15); do
    code="$(curl -sk -o /dev/null -w '%{http_code}' -m 5 https://127.0.0.1/party/api/v1/bff/login || true)"
    [ "$code" = 302 ] && { log "  게이트웨이 경유 /party/api/v1/bff/login: $code"; return 0; }
    sleep 2
  done
  log "  게이트웨이 경유 로그인 시작이 302 가 아니다(마지막 응답: ${code:-없음})"; return 1
}

log "== 교체 =="
PARKED=""
if [ "$MIGRATE" = true ]; then
  PARKED="$CONTAINER-pre-split-$TS"
  log "  $CURRENT_PROJECT 소속 $CONTAINER 를 멈추고 $PARKED 로 이름을 바꿔 둔다(실패하면 되돌린다)"
  docker stop "$CONTAINER" >/dev/null
  docker rename "$CONTAINER" "$PARKED"
fi
compose up -d --no-build "$SERVICE" 2>&1 | tail -3

if wait_healthy && reload_gateway && gateway_ok; then
  if [ -n "$PARKED" ]; then docker rm "$PARKED" >/dev/null && log "  옛 컨테이너 $PARKED 를 지웠다(옮기기 완료)"; fi
  # 롤백 이미지는 최근 ROLLBACK_KEEP 개만 남긴다. 실행 중인 이미지는 docker 가 지우지 않는다.
  docker images doro-bff-rollback --format '{{.Tag}}' | sort -r | tail -n +"$((${ROLLBACK_KEEP:-5} + 1))" | while read -r tag; do
    docker rmi "doro-bff-rollback:$tag" >/dev/null 2>&1 || true
  done
  log "완료. 롤백 이미지: doro-bff-rollback:$TS"
  exit 0
fi

log "ERROR: 새 BFF 가 정상이 아니다. 직전 상태로 되돌린다."
docker logs --tail 40 "$CONTAINER" 2>&1 || true
if [ -n "$PARKED" ]; then
  compose rm -sf "$SERVICE" >/dev/null 2>&1 || true
  docker rename "$PARKED" "$CONTAINER" && docker start "$CONTAINER" >/dev/null
elif [ -n "$OLD_IMAGE" ]; then
  docker tag "$OLD_IMAGE" "$IMAGE"
  compose up -d --no-build --force-recreate "$SERVICE" 2>&1 | tail -3
else
  die "되돌릴 직전 상태가 없다(첫 배포). 수동 확인 필요"
fi
if wait_healthy && reload_gateway && gateway_ok; then log "복구 완료: 직전 BFF 가 서비스 중이다."; else log "ERROR: 복구 후에도 정상이 아니다. 수동 확인 필요."; fi
exit 1
