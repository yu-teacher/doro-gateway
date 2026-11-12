#!/usr/bin/env bash
# 게이트웨이 설정의 "라우팅 결과"를 로컬 Docker 에서 시험한다(운영 서버는 건드리지 않는다).
# 각 업스트림 이름(web, auth-api, doro-blog-web ...)을 가진 작은 nginx 를 띄워 자기 이름과 받은 경로를 돌려주게 하고,
# 시험 대상 설정으로 만든 게이트웨이에 요청을 보내 "어느 업스트림이 어떤 경로로 받았는지"를 확인한다.
#
#   scripts/test-gateway-routing.sh <nginx.conf> <expectations-file>   (현재 기대 파일: scripts/gateway-routing/expect-current.txt, 옛 단계 파일은 마이그레이션 기록이다)
#
# 기대 파일(탭 구분 없이 공백): <메서드> <경로> <기대 상태> <기대 업스트림 또는 'redirect:<Location>'> [<기대 업스트림이 받은 경로>] [app=<업스트림이 받은 X-Doro-Notify-App 값, 빈 값은 'app='>]
# 모든 요청에 위조 헤더 X-Doro-Notify-App: spoof 를 실어 보내므로, app= 기대로 게이트웨이가 이 헤더를 덮어쓰거나 지우는지 확인한다.
set -Eeuo pipefail

CONF="${1:?시험할 nginx.conf 경로가 필요하다}"
EXPECT="${2:?기대 파일이 필요하다}"
NET="gwtest-$$"
WORK="$(mktemp -d)"
IMG_NGINX="${GW_TEST_NGINX_IMAGE:-nginx:1.27-alpine}"
IMG_CURL="${GW_TEST_CURL_IMAGE:-curlimages/curl:8.10.1}"
NAMES=(web auth-api doro-blog-web doro-blog-backend doro-minio doro-menu doro-games-web doro-games-api doro-interview-web doro-interview-api doro-party-web doro-party-api doro-bff-api doro-notify-api loki-central)
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
cleanup() {
  for n in "${NAMES[@]}" gw; do docker rm -f "gwt-$$-$n" >/dev/null 2>&1 || true; done
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

docker network create "$NET" >/dev/null

# 1) 업스트림 흉내: 자기 이름과 받은 경로(request_uri)를 헤더와 본문으로 돌려준다. 어떤 경로든 200.
#    이름은 nginx 이미지의 템플릿 치환(envsubst)으로 넣는다: /etc/nginx/templates/*.template 의 ${STUB_NAME} 이 시작 때 채워진다.
#    (마운트한 파일을 sed -i 로 고치려 하면 "Resource busy" 로 컨테이너가 죽는다.)
cat > "$WORK/stub.conf.template" <<'STUBEOF'
server {
    listen 80;
    listen 3100; listen 8082; listen 8086; listen 8087; listen 8088; listen 8085; listen 8089; listen 8080; listen 9000;
    location / {
        add_header X-Stub-Name "${STUB_NAME}" always;
        add_header X-Stub-Path "$request_uri" always;
        add_header X-Stub-Notify-App "$http_x_doro_notify_app" always;
        default_type text/plain;
        return 200 "stub=${STUB_NAME} path=$request_uri\n";
    }
}
STUBEOF
for n in "${NAMES[@]}"; do
  docker run -d --name "gwt-$$-$n" --network "$NET" --network-alias "$n" -e STUB_NAME="$n" \
    -v "$WORK/stub.conf.template:/etc/nginx/templates/default.conf.template:ro" "$IMG_NGINX" >/dev/null
done

# 2) 시험 대상 설정: 운영 인증서 경로는 자체 서명 인증서로 바꾸고, 중앙 Loki IP 는 업스트림 이름으로 바꾼다. 업스트림 이름은 그대로 쓴다.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=localhost" -keyout "$WORK/key.pem" -out "$WORK/cert.pem" >/dev/null 2>&1
sed -e "s#/etc/letsencrypt/live/[^/]*/fullchain.pem#/certs/cert.pem#" \
    -e "s#/etc/letsencrypt/live/[^/]*/privkey.pem#/certs/key.pem#" \
    -e "s#server 192\.168\.[0-9.]*:3100;#server loki-central:3100;#" "$CONF" > "$WORK/nginx.conf"
docker run -d --name "gwt-$$-gw" --network "$NET" --network-alias gateway \
  -v "$WORK/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$WORK:/certs:ro" "$IMG_NGINX" >/dev/null
sleep 2
if ! docker exec "gwt-$$-gw" nginx -t >/dev/null 2>&1; then docker exec "gwt-$$-gw" nginx -t 2>&1 | tail -3; echo "게이트웨이 설정이 시작되지 못한다"; exit 1; fi

# 3) 요청을 보내고 기대와 비교한다.
pass=0; fail=0
while read -r method path status want_up want_path want_app; do
  [ -z "${method:-}" ] && continue
  case "$method" in \#*) continue ;; esac
  out="$(docker run --rm --network "$NET" "$IMG_CURL" -sk -m 10 -H 'X-Doro-Notify-App: spoof' -X "$method" -o /dev/null -D - -w 'CODE:%{http_code}\n' "https://gateway$path" 2>/dev/null | tr -d '\r')"
  code="$(printf '%s\n' "$out" | sed -n 's/^CODE://p')"
  up="$(printf '%s\n' "$out" | sed -n 's/^[Xx]-[Ss]tub-[Nn]ame: //p' | head -1)"
  got_path="$(printf '%s\n' "$out" | sed -n 's/^[Xx]-[Ss]tub-[Pp]ath: //p' | head -1)"
  got_app="$(printf '%s\n' "$out" | sed -n 's/^[Xx]-[Ss]tub-[Nn]otify-[Aa]pp: *//p' | head -1)"
  # Location 은 nginx 가 요청 호스트를 붙인 절대 주소(https://gateway/blog/)로 내보내는 것이 정상이다. 비교는 호스트를 뗀 경로로 한다.
  loc="$(printf '%s\n' "$out" | sed -n 's/^[Ll]ocation: //p' | head -1 | sed -E 's#^https?://[^/]+##')"
  ok=true; detail="상태=$code"
  [ "$code" = "$status" ] || ok=false
  case "$want_up" in
    redirect:*) [ "$loc" = "${want_up#redirect:}" ] || ok=false; detail="$detail Location=$loc" ;;
    none) [ -z "$up" ] || ok=false; detail="$detail 업스트림=${up:-없음}" ;;
    *) [ "$up" = "$want_up" ] || ok=false; detail="$detail 업스트림=${up:-없음}"
       if [ -n "${want_path:-}" ]; then [ "$got_path" = "$want_path" ] || ok=false; detail="$detail 받은경로=$got_path"; fi
       if [ -n "${want_app:-}" ]; then [ "app=$got_app" = "$want_app" ] || ok=false; detail="$detail 앱헤더=$got_app"; fi ;;
  esac
  if [ "$ok" = true ]; then pass=$((pass + 1)); printf '  통과  %-5s %-52s %s\n' "$method" "$path" "$detail"
  else fail=$((fail + 1)); printf '  실패  %-5s %-52s %s   (기대: 상태=%s %s %s)\n' "$method" "$path" "$detail" "$status" "$want_up" "${want_path:-}"; fi
done < "$EXPECT"
echo "결과: 통과 $pass, 실패 $fail"
[ "$fail" -eq 0 ]
