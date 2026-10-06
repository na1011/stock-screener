#!/bin/bash
#
# 새 jar를 빌드하고 도는 인스턴스를 갈아 끼운다.
#
# 스크립트로 두는 이유는 순서가 틀리기 쉽기 때문이다 — 재기동을 먼저 하면 옛 jar가
# 다시 뜨고, 빌드만 하면 도는 것은 계속 옛것이다. 그 상태는 "고쳤는데 왜 안 바뀌지"로만
# 보이고, 화면에는 아무 단서가 없다.
#
# **터널은 도는 중이면 건드리지 않고, 꺼져 있으면 켠다.** 바뀐 것은 jar뿐이라 도는 터널을
# 함께 내렸다 올리면 재배포마다 밖에서 안 보이는 시간만 길어진다. 반대로 꺼져 있는 것을
# 그대로 두면 "앱은 떴는데 배포는 실패"라는 상태로 끝나, 실제로 무엇이 됐는지 읽히지 않는다.
#
# 그래서 이 스크립트는 **어느 상태에서 실행해도 같은 곳에 도착한다** — 새 jar가 돌고
# 밖에서 보인다. 둘 다 켜져 있든, 앱만 켜져 있든, 아무것도 안 돌든 그대로 실행하면 된다.
# **예외는 하나다 — 앱 크론이 남았으면 아무것도 바꾸지 않고 멈춘다**(ops/crons-done.sh). 판정은 빌드 앞과 종료 직전
# 두 번 묻고, 스테이징은 종료가 끝난 뒤에만 한다 — 멈춘 배포가 배포 폴더(jar · app.jar.commit)를 새것으로 바꿔 두면
# 다음 기동이 말없이 그 jar를 띄운다(2026-10-06 반증). 사람이 판정을 넘기려면 ops/deploy.sh --force.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=ops/lib.sh
. "$HERE/lib.sh"
cd "$ROOT"

# 빌드 앞에서 한 번 알린다. 이 배포는 곧 앱을 내리므로 도는 배치가 있으면 끊긴다.
# 여기서 알리는 이유는 **빌드가 분 단위라, 끝난 뒤에 알면 이미 늦기 때문**이다 — 지금이라야
# Ctrl-C로 되돌릴 수 있다. 다만 그 몇 분 사이에 시작된 잡은 여기서 안 보인다.
# 정본은 stop.sh 쪽 호출이다(kill 직전에 다시 묻는다).
if running_pid "$APP_PID_FILE" "$APP_PATTERN" >/dev/null; then
  warn_running_batches "deploy"
fi

# 앱을 내려도 그날 크론이 안 빠지는가 — 빌드(분 단위) 전에 한 번, 종료 직전에 한 번 묻는다.
FORCE=""
for arg in "$@"; do
  case "$arg" in
    --force) FORCE="--force" ;;
    *)       echo "용법: ops/deploy.sh [--force]" >&2; exit 2 ;;
  esac
done
crons_gate() {
  local judged=0
  [[ "$FORCE" == "--force" ]] && return 0
  running_pid "$APP_PID_FILE" "$APP_PATTERN" >/dev/null || return 0
  "$HERE/crons-done.sh" || judged=$?
  [[ $judged -eq 0 ]] && return 0
  if [[ $judged -eq 2 ]]; then
    echo "[deploy] 앱 크론이 남았는지 판정하지 못했다(위 사유) — 아무것도 바꾸지 않고 멈춘다." >&2
  else
    echo "[deploy] 앱 크론이 아직 남았다(위 목록) — 아무것도 바꾸지 않고 멈춘다." >&2
  fi
  echo "         기다렸다 다시 친다. 사람이 판정을 넘기려면: ops/deploy.sh --force" >&2
  exit 1
}
crons_gate

# jar가 나온 커밋을 빌드 전후로 잡는다 — 빌드(약 1분) 사이에 공유 작업 트리로 커밋이 들어오면 「스테이징 순간의 HEAD」는
# 다른 코드를 가리킨다. 둘이 같고 빌드 입력에 커밋 안 된 것이 없을 때만 stage.sh가 커밋을 적는다.
build_state() {
  echo "$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)|$(git -C "$ROOT" status --porcelain -- src frontend gradle build.gradle settings.gradle gradlew 2>/dev/null | md5)"
}
BUILD_STATE_BEFORE="$(build_state)"

# 시험 DB에 이 트리에 없는 마이그레이션이 있으면 빌드 전에 멈춘다 — 시험 프로파일의 V900 때문에 그 버전은 future로
# 안 무시되고 「적용됐는데 코드에 없음」으로 Flyway 검증이 서서, 시험 수백 개가 코드 결함처럼 붉는다(2026-10-06: 다른
# 브랜치 시험이 기본 시험 DB에 V63·V64를 넣어 main 빌드가 736 실패 · 빈 DB 대조군은 통과). 기본 시험 DB는 공유라 막을 수 없고,
# 배포 빌드 앞에서 사유로 멈추는 것이 이 자리의 몫이다.
test_db_guard() {
  local url="${TEST_DB_URL:-jdbc:postgresql://localhost:5432/stock_screener_test}" db psql versions v extra=""
  db="${url##*/}"; db="${db%%\?*}"
  psql="$(pg_bin psql)" || { echo "[deploy] psql을 못 찾아 시험 DB(${db})를 판정하지 못했다 — 멈춘다." >&2; exit 1; }
  # 서버 메시지는 로캘을 따라 한국어로 오므로 문구로 가르지 않는다 — 표가 있는지를 먼저 묻는다.
  if ! versions="$(PGCONNECT_TIMEOUT=5 "$psql" -X -w -At -d "$db" -c "select to_regclass('flyway_schema_history') is not null" 2>&1)"; then
    echo "[deploy] 시험 DB(${db})를 읽지 못했다 — 멈춘다: ${versions}" >&2
    exit 1
  fi
  [[ "$versions" == "f" ]] && return 0   # 빈 시험 DB — 시험이 첫 실행에 마이그레이션을 적용한다
  if ! versions="$(PGCONNECT_TIMEOUT=5 "$psql" -X -w -At -d "$db" -c "select version from flyway_schema_history where success and version is not null" 2>&1)"; then
    echo "[deploy] 시험 DB(${db})를 읽지 못했다 — 멈춘다: ${versions}" >&2
    exit 1
  fi
  while IFS= read -r v; do
    [[ -z "$v" ]] && continue
    if ! compgen -G "$ROOT/src/main/resources/db/migration/V${v}__*.sql" >/dev/null \
        && ! compgen -G "$ROOT/src/test/resources/db/testmigration/V${v}__*.sql" >/dev/null; then
      extra="${extra} V${v}"
    fi
  done <<< "$versions"
  if [[ -n "$extra" ]]; then
    echo "[deploy] 시험 DB(${db})에 이 트리에 없는 마이그레이션이 적용돼 있다:${extra} — 다른 브랜치 시험이 남긴 것이다." >&2
    echo "         빌드가 Flyway 검증에서 멈춘다. 그 DB를 이름을 바꿔 보존하고 같은 이름으로 새로 만든 뒤(또는 TEST_DB_URL을 깨끗한 DB로) 다시 친다." >&2
    exit 1
  fi
}
test_db_guard

echo "== 빌드 =="
./gradlew clean build
BUILD_STATE_AFTER="$(build_state)"
export BUILD_STATE_BEFORE BUILD_STATE_AFTER

echo "== 앱 재기동 =="
# 스테이징의 전제(jar · .env · 배포 폴더)를 종료 **전에** 묻는다 — 스테이징은 종료 뒤라, 여기서 걸릴 것이 종료 뒤에 걸리면
# 사이트가 내려간 채 남는다. 여기서 멈추면 아직 아무것도 안 바꿨다.
"$HERE/stage.sh" --check
# 빌드 사이에 명목 시각이 지났을 수 있다 — 종료 직전에 다시 묻는다. 여기서 멈추면 아직 아무것도 안 바꿨다.
crons_gate
# 돌고 있지 않아도 실패로 다루지 않는다. 배포는 "지금 도는 것을 새것으로 바꾼다"이고,
# 꺼져 있었으면 새것으로 켜는 것이 같은 의도다. 판정은 위에서 했으므로 stop.sh에는 --force를 넘긴다.
if running_pid "$APP_PID_FILE" "$APP_PATTERN" >/dev/null; then
  "$HERE/stop.sh" --app --force
fi

# 여기부터 실패하면 앱은 내려간 채다 — 조용히 rc 1로 끝내지 않고 그 상태와 되살리는 명령을 찍는다.
app_down() {
  echo "[deploy] $1 — 앱이 내려간 채다(위 사유). 원인을 고친 뒤 ${2}." >&2
  echo "         배포 폴더의 app.jar.commit이 없거나 이번 커밋이 아니면 jar도 이번 것이 아니다 — 스테이징부터 다시 한다." >&2
  exit 1
}
echo "== 스테이징 =="
# 종료가 끝난 뒤에만 배포 폴더를 바꾼다 — 그래야 「app.jar.commit = 지금 도는(또는 곧 뜰) jar」가 선다.
"$HERE/stage.sh" || app_down "스테이징이 실패했다" "ops/stage.sh → ops/start.sh --app"
"$HERE/start.sh" --app || app_down "앱이 뜨지 않았다" "ops/start.sh --app"

echo "== 터널 =="
if running_pid "$TUNNEL_PID_FILE" "$TUNNEL_PATTERN" >/dev/null; then
  echo "[deploy] 이미 돌고 있다 — 건드리지 않는다."
else
  "$HERE/start.sh" --tunnel
fi

echo "== 확인 =="
# 밖에서 물어야 앱과 터널이 갈린다. 로컬만 보면 cloudflared가 죽어도 초록이다.
# 터널이 붙는 데 몇 초 걸리므로 한 번만 던지지 않는다.
echo -n "외부: "
if wait_for "$HEALTH_PUBLIC" 30; then
  curl -s "$HEALTH_PUBLIC"; echo
else
  echo "응답 없음 — 앱은 위에서 떴으니 터널 쪽이다. $LOG_DIR/tunnel.log를 본다." >&2
  exit 1
fi
