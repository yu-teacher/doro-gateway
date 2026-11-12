#!/usr/bin/env bash
# BFF 시험: H2 에서만 의미 있는 시험 + 나머지 전체를 실제 PostgreSQL 위에서(Flyway + ddl-auto=validate).
# Doro 저장소에서 옮겨 오기 전과 같은 구성이다(H2 시험을 PostgreSQL 단계와 되풀이하지 않는다).
#
#   scripts/ci-test.sh            # 둘 다(CI 와 같다). 필요: Docker, Java 25, 형제 디렉터리 ../Doro(.doro-sdk-ref 의 태그)
#   scripts/ci-test.sh h2         # H2 단계만
#   scripts/ci-test.sh pg         # PostgreSQL 단계만
#
# 시험용 PostgreSQL 은 무작위 이름의 일회용 컨테이너(127.0.0.1 의 빈 포트)이며 끝나면 지운다.
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
TARGET="${1:-all}"
case "$TARGET" in all|h2|pg) ;; *) echo "사용법: scripts/ci-test.sh [all|h2|pg]" >&2; exit 2 ;; esac
[ -d ../Doro/sdk ] || { echo "../Doro 가 없다(SDK 를 소스로 빌드한다). Doro 저장소를 $(cat .doro-sdk-ref) 태그로 형제 디렉터리에 체크아웃한다." >&2; exit 2; }

if [ "$TARGET" != pg ]; then
  # H2 단계에는 TEST_PG_URL 을 넘기지 않는다(그 값이 있으면 PostgreSQL 전용 시험이 켜진다).
  env -u TEST_PG_URL ./gradlew testH2Only --no-daemon --console=plain
fi
[ "$TARGET" = h2 ] && exit 0

PG_NAME="doro-bff-test-pg-$$"
PG_PASSWORD="$(openssl rand -hex 16)"
cleanup() { docker rm -f "$PG_NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --name "$PG_NAME" -p 127.0.0.1::5432 -e POSTGRES_PASSWORD="$PG_PASSWORD" postgres:16-alpine >/dev/null
# pg_isready(유닉스 소켓)는 이미지 초기화 중의 임시 서버에도 성공한다. 임시 서버는 TCP 를 열지 않으므로 TCP 질의로 기다린다.
for _ in $(seq 1 60); do docker exec "$PG_NAME" psql -h 127.0.0.1 -U postgres -tAc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
docker exec "$PG_NAME" psql -h 127.0.0.1 -U postgres -tAc 'select 1' >/dev/null || { echo "PostgreSQL 이 기동되지 않았다" >&2; exit 1; }
docker exec "$PG_NAME" psql -U postgres -qc "CREATE SCHEMA suite_bff;"
PORT="$(docker port "$PG_NAME" 5432/tcp | head -1 | sed 's/.*://')"

TEST_PG_URL="jdbc:postgresql://127.0.0.1:$PORT/postgres" TEST_PG_USER=postgres TEST_PG_PASSWORD="$PG_PASSWORD" \
  ./gradlew testOnPostgres --no-daemon --console=plain
