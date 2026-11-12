#!/usr/bin/env bash
# 서버(mini)에서 한 번 실행한다: BFF 를 Doro 저장소에서 이 저장소로 옮길 때의 서버 준비. 여러 번 실행해도 결과가 같다(멱등).
# 비밀 값은 화면에 출력하지 않는다(키 이름과 "같다/다르다"만 출력한다).
#
#   ssh mini 'bash -s -- --check' < scripts/setup-gateway-server.sh   # 읽기 전용: 무엇이 있고 무엇을 만들지
#   ssh mini 'bash -s -- --apply' < scripts/setup-gateway-server.sh   # ~/doro-gateway/.env 를 만든다
#
# 하는 일
#   1) ~/doro-gateway 폴더(700)를 만든다.
#   2) ~/doro-gateway/.env(600)를 ~/doro/.env 의 BFF 값으로 만든다(이미 있으면 건드리지 않는다).
#        BFF_DB_USER → DB_USER, BFF_DB_PASSWORD → DB_PASSWORD, BFF_SESSION_KEY, BFF_IAM_INTERNAL_TOKEN, DORO_IAM_ISSUER,
#        (있으면) BFF_PUBLIC_ORIGIN, BFF_DB, BFF_PORT
#   3) 지금 떠 있는 doro-bff-api 컨테이너가 실제로 쓰는 값과 새 .env 의 값이 같은지 해시로 비교한다.
# 하지 않는 일: 컨테이너 교체(첫 배포가 한다), 러너 등록, ~/doro/.env 수정(옛 BFF_* 값은 Doro 쪽 정리 뒤에 지워도 된다).
set -Eeuo pipefail

DIR="${GATEWAY_DIR:-$HOME/doro-gateway}"
DORO_ENV="${DORO_ENV:-$HOME/doro/.env}"
CONTAINER="${BFF_CONTAINER:-doro-bff-api}"
ENV_FILE="$DIR/.env"
MODE="${1:---check}"
case "$MODE" in --check|--apply) ;; *) echo "사용법: bash -s -- --check | --apply" >&2; exit 2 ;; esac

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
has_key() { grep -q "^$2=" "$1" 2>/dev/null; }
# 값의 해시(앞 12자리)만 쓴다. 값은 변수에도 오래 두지 않는다.
file_hash() { grep -m1 "^$2=" "$1" | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/" | tr -d '\n' | sha256sum | cut -c1-12; }
container_hash() { docker inspect "$CONTAINER" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -m1 "^$1=" | cut -d= -f2- | tr -d '\n' | sha256sum | cut -c1-12; }

# 새 .env 키 ← ~/doro/.env 키 (필수)
REQUIRED="DB_USER:BFF_DB_USER DB_PASSWORD:BFF_DB_PASSWORD BFF_SESSION_KEY:BFF_SESSION_KEY BFF_IAM_INTERNAL_TOKEN:BFF_IAM_INTERNAL_TOKEN DORO_IAM_ISSUER:DORO_IAM_ISSUER"
OPTIONAL="BFF_PUBLIC_ORIGIN:BFF_PUBLIC_ORIGIN BFF_DB:BFF_DB BFF_PORT:BFF_PORT"

log "== 현재 상태 =="
[ -f "$DORO_ENV" ] || die "$DORO_ENV 가 없다"
for pair in $REQUIRED; do
  src="${pair#*:}"
  has_key "$DORO_ENV" "$src" || die "$DORO_ENV 에 $src 가 없다(옮길 값이 없다)"
done
log "  $DORO_ENV 에 필요한 BFF 키가 모두 있다"
if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  log "  $CONTAINER: compose 프로젝트 '$(docker inspect "$CONTAINER" --format '{{index .Config.Labels "com.docker.compose.project"}}')', 상태 $(docker inspect "$CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null || echo '?')"
else
  log "  $CONTAINER 가 없다"
fi
if [ -f "$ENV_FILE" ]; then log "  $ENV_FILE 이 이미 있다(덮어쓰지 않는다)"; else log "  $ENV_FILE 이 없다 → --apply 가 만든다"; fi

if [ "$MODE" = --apply ] && [ ! -f "$ENV_FILE" ]; then
  log "== .env 만들기 =="
  mkdir -p "$DIR"; chmod 700 "$DIR"
  tmp="$(mktemp "$DIR/.env.XXXXXX")"; chmod 600 "$tmp"
  {
    echo "# doro-gateway(BFF) 서버 설정. $(date +%F) 에 $DORO_ENV 의 BFF 값으로 만들었다(setup-gateway-server.sh)."
    for pair in $REQUIRED $OPTIONAL; do
      dst="${pair%%:*}"; src="${pair#*:}"
      grep -m1 "^$src=" "$DORO_ENV" | sed "s/^$src=/$dst=/" || true
    done
  } > "$tmp"
  mv "$tmp" "$ENV_FILE"
  log "  만들었다: $ENV_FILE (키: $(grep -oE '^[A-Z_]+=' "$ENV_FILE" | tr -d = | tr '\n' ' '))"
fi

if [ -f "$ENV_FILE" ] && docker inspect "$CONTAINER" >/dev/null 2>&1; then
  log "== 지금 컨테이너가 쓰는 값과 비교(해시) =="
  bad=0
  for pair in DB_USER:SPRING_DATASOURCE_USERNAME DB_PASSWORD:SPRING_DATASOURCE_PASSWORD BFF_SESSION_KEY:BFF_SESSION_KEY BFF_IAM_INTERNAL_TOKEN:DORO_IAM_INTERNAL_TOKEN DORO_IAM_ISSUER:DORO_IAM_ISSUER; do
    k="${pair%%:*}"; ck="${pair#*:}"
    if [ "$(file_hash "$ENV_FILE" "$k")" = "$(container_hash "$ck")" ]; then log "  $k: 같다"; else log "  $k: 다르다"; bad=1; fi
  done
  [ "$bad" = 0 ] || die "새 .env 값이 지금 컨테이너와 다르다. 첫 배포 전에 원인을 확인한다(세션 키가 다르면 모두 로그아웃된다)"
  log "  모두 같다. 이제 러너를 등록하고 저장소 변수 CD_ENABLED=true 를 켠 뒤 배포한다."
fi
