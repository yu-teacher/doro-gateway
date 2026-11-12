#!/usr/bin/env bash
# 서버(mini)에서 실행한다: 새 nginx.conf 후보 파일을 검증하고 게이트웨이에 반영한다. 여러 번 실행해도 같은 결과(멱등).
# 게이트웨이는 compose 밖에 있고 nginx.conf 를 파일 하나로 마운트하므로 같은 inode 로 덮어쓴다(mv 로 바꾸면 컨테이너가 옛 파일을 계속 본다).
# 순서: 후보를 컨테이너 안에서 nginx -t 로 검증 → 현재 파일 백업 → 덮어쓰기 → reload → 확인 실패 시 백업으로 되돌림.
#
#   scp gateway/nginx.conf mini:doro/gateway/nginx.conf.new
#   ssh mini 'bash -s -- --dry-run' < scripts/apply-gateway-conf.sh     # 바뀔 내용과 검증만
#   ssh mini 'bash -s -- --apply'   < scripts/apply-gateway-conf.sh     # 반영
set -Eeuo pipefail

CONF="${GATEWAY_CONF:-$HOME/doro/gateway/nginx.conf}"
NEW="${GATEWAY_CONF_NEW:-$HOME/doro/gateway/nginx.conf.new}"
CONTAINER="${GATEWAY_CONTAINER:-doro-gateway}"
APPLY=false
for a in "$@"; do
  case "$a" in
    --apply) APPLY=true ;;
    --dry-run) APPLY=false ;;
    *) echo "알 수 없는 옵션: $a" >&2; exit 2 ;;
  esac
done
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
[ -f "$CONF" ] || die "$CONF 가 없다"
[ -f "$NEW" ] || die "$NEW 가 없다(먼저 scp 로 올린다)"
docker inspect "$CONTAINER" >/dev/null 2>&1 || die "$CONTAINER 컨테이너가 없다"

if cmp -s "$CONF" "$NEW"; then
  log "변경 없음: 이미 반영돼 있다."
  exit 0
fi
log "바뀔 내용(줄 수): $(diff "$CONF" "$NEW" | grep -c '^[<>]')줄"
diff "$CONF" "$NEW" | head -60 || true

# 후보 검증: 현재 파일과 같은 마운트 조건에서 nginx -t. 업스트림 호스트 이름을 해석해야 하므로 게이트웨이와 같은 네트워크에서 돌린다.
IMG="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")"
NETS="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$CONTAINER" | awk '{print $1}')"
# 게이트웨이 컨테이너의 바인드 마운트(인증서 등)를 그대로 재현하고 nginx.conf 만 후보로 바꾼다.
VOLS=()
while IFS='|' read -r src dst mode; do
  [ -z "$src" ] && continue
  [ "$dst" = "/etc/nginx/nginx.conf" ] && continue
  VOLS+=(-v "$src:$dst:ro")
done < <(docker inspect -f '{{range .Mounts}}{{if eq .Type "bind"}}{{.Source}}|{{.Destination}}|{{.Mode}}{{"\n"}}{{end}}{{end}}' "$CONTAINER")
VOLS+=(-v "$NEW:/etc/nginx/nginx.conf:ro")
if docker run --rm --network "$NETS" "${VOLS[@]}" "$IMG" nginx -t >/tmp/gw-test.out 2>&1; then
  log "nginx -t 통과"
else
  cat /tmp/gw-test.out >&2; die "후보 설정 검증 실패 — 아무것도 바꾸지 않았다"
fi
$APPLY || { log "dry-run 이라 반영하지 않았다."; exit 0; }

BAK="$CONF.bak-$(date +%Y%m%d-%H%M%S)"
cp -p "$CONF" "$BAK"
cat "$NEW" > "$CONF"
if docker exec "$CONTAINER" nginx -t >/dev/null 2>&1 && docker exec "$CONTAINER" nginx -s reload; then
  sleep 2
  CODE="$(curl -sk -o /dev/null -w '%{http_code}' https://localhost/ -H 'Host: varen05.asuscomm.com' || true)"
  if [ "$CODE" = 200 ]; then
    log "반영 완료(허브 응답 $CODE). 백업: $BAK"
    exit 0
  fi
  log "reload 뒤 허브 응답이 $CODE — 되돌린다"
else
  log "컨테이너 안 검증/reload 실패 — 되돌린다"
fi
cat "$BAK" > "$CONF"
docker exec "$CONTAINER" nginx -s reload || true
die "되돌렸다. 원인을 확인하세요."
