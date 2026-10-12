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
# 쓰기 잠금(ADR-0175 · 잠금 결정 6·11). 둘을 가른다:
#   - 남(bootRun · 손 세션 · 다른 JVM)이 쥐고 있으면 --force와 무관하게 멈춘다 — 새 앱은 기동 구간(Flyway·시더·스위퍼)을 잠금
#     안에서 쓰므로 그 동안 못 뜬다(start.sh가 거절한다). 여기서 멈추지 않으면 옛 앱만 내리고 새 앱은 못 올려 사이트가 내려간 채다.
#   - 앱 JVM 자신이 쥐고 있으면(크론·관리자 잡·판정 PUT이 쓰는 중) 크론 판정과 같이 --force가 넘긴다.
lock_gate() {
  local db="${DB_NAME:-stock_screener}" app_pid holder rc=0 other=0
  app_pid="$(running_pid "$APP_PID_FILE" "$APP_PATTERN" || true)"
  holder="$(write_lock_held_by_other "$db" "$app_pid")" || other=$?
  if [[ $other -eq 0 ]]; then
    echo "[deploy] 쓰기 잠금을 ${holder}이(가) 쥐고 있다 — 새 앱은 기동에서 잠금을 쥐므로 지금은 못 뜬다. 아무것도 바꾸지 않고 멈춘다." >&2
    echo "         그것이 끝난 뒤 다시 친다(손 세션이면 ops/write-session.sh end <id> → 배포 → begin)." >&2
    exit 1
  elif [[ $other -eq 2 ]]; then
    # MUST: 뒤 단계(운영 마이그레이션 점검)가 같은 DB에 못 닿아 멈출 것이라도 여기서 멈춘다 — 다른 문의 순서에 기대지 않는다.
    echo "[deploy] 쓰기 잠금 보유자를 판정하지 못했다(psql · DB ${db}) — 아무것도 바꾸지 않고 멈춘다." >&2
    exit 1
  fi
  [[ "$FORCE" == "--force" || -z "$app_pid" ]] && return 0
  holder="$(write_lock_held_by_jvm "$db" "$app_pid")" || rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "[deploy] 앱 JVM이 쓰기 잠금을 쥐고 있다(${holder}) — 쓰는 중이라 아무것도 바꾸지 않고 멈춘다." >&2
    echo "         끝난 뒤 다시 친다. 사람이 판정을 넘기려면: ops/deploy.sh --force" >&2
    exit 1
  elif [[ $rc -eq 2 ]]; then
    echo "[deploy] 쓰기 잠금을 앱 JVM이 쥐었는지 판정하지 못했다(psql) — 아무것도 바꾸지 않고 멈춘다." >&2
    exit 1
  fi
}
# 적재 JVM(bootRun)이 돌고 있으면 멈춘다(--force와 무관) — 배포를 가로지르는 bootRun이 옛 jar면 그 실행 행은 주인 칸이 비어
# 있어, 새 jar의 bootRun 스윕이 「주인 없는 옛 행」 규칙으로 그 산 행을 닫을 수 있다(ADR-0175 과도기). 새 jar의 bootRun이면 잠금을
# 쥐고 있어 새 앱이 어차피 못 뜬다. 판정은 crons-done.sh·훅과 같은 함수다. 판정하지 못했으면(2) 멈춘다 — 「없다」로 읽지 않는다.
load_gate() {
  local lib="$ROOT/.claude/hooks/lib/load-process.sh" pids rc=0
  if [[ ! -r "$lib" ]]; then
    echo "[deploy] 적재 JVM 판정 함수를 못 읽었다(${lib}) — 아무것도 바꾸지 않고 멈춘다." >&2
    exit 1
  fi
  # shellcheck source=.claude/hooks/lib/load-process.sh
  . "$lib"
  pids="$(stray_load_pids)" || rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "[deploy] 적재 JVM(bootRun)이 돌고 있다(PID ${pids}) — 끝난 뒤 배포한다. 아무것도 바꾸지 않고 멈춘다." >&2
    exit 1
  elif [[ $rc -ne 1 ]]; then
    echo "[deploy] 적재 JVM이 도는지 판정하지 못했다(pgrep·ps) — 아무것도 바꾸지 않고 멈춘다." >&2
    exit 1
  fi
}
crons_gate
lock_gate
load_gate

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
  if ! versions="$(PGCONNECT_TIMEOUT=5 "$psql" -X -w -At -d "$db" -c "select version || '|' || success from flyway_schema_history where version is not null" 2>&1)"; then
    echo "[deploy] 시험 DB(${db})를 읽지 못했다 — 멈춘다: ${versions}" >&2
    exit 1
  fi
  # 쓰기 가드 스위치가 남아 있는가(ADR-0175 · 잠금 결정 3). 시험은 가드를 자기 JVM 신원 전체(ss:j:<pid>:<시작초>) 범위로 켜고 끝에
  # 지운다 — 죽은 시험이 남긴 그 꼴의 행은 같은 신원이 다시 안 나오므로(시작 초가 다르다) 무해하다. 막는 것은 셋이다: 범위가
  # NULL(그 DB의 모든 쓰기) · 살아 있는 시험 JVM의 행(빌드와 겹친다) · 시작 초 없는 옛 꼴(pid 재사용에 걸린다).
  # MUST: 표 존재는 따로 묻는다 — 같은 문장 안에서 표를 부르면 V66 전 DB에서 파싱 단계에 죽는다.
  local has_guard rows row prefix gpid gstart blockers="" harmless=""
  if ! has_guard="$(PGCONNECT_TIMEOUT=5 "$psql" -X -w -At -d "$db" -c "select to_regclass('public.guard_setting') is not null" 2>&1)"; then
    echo "[deploy] 시험 DB(${db})의 쓰기 가드 스위치를 읽지 못했다 — 멈춘다: ${has_guard}" >&2
    exit 1
  fi
  if [[ "$has_guard" == "t" ]]; then
    if ! rows="$(PGCONNECT_TIMEOUT=5 "$psql" -X -w -At -d "$db" -c "select coalesce(identity_prefix, '(전부)') from guard_setting where db_name = current_database()" 2>&1)"; then
      echo "[deploy] 시험 DB(${db})의 쓰기 가드 스위치를 읽지 못했다 — 멈춘다: ${rows}" >&2
      exit 1
    fi
    while IFS= read -r prefix; do
      [[ -z "$prefix" ]] && continue
      if [[ "$prefix" =~ ^ss:j:([0-9]+):([0-9]+)$ ]]; then
        gpid="${BASH_REMATCH[1]}"; gstart="${BASH_REMATCH[2]}"
        if proc_alive "$gpid" "$gstart"; then
          blockers="${blockers} ${prefix}(살아 있는 시험 JVM)"
        else
          harmless="${harmless} ${prefix}"
        fi
      else
        blockers="${blockers} ${prefix}"
      fi
    done <<< "$rows"
  fi
  if [[ -n "$harmless" ]]; then
    echo "[deploy] 시험 DB(${db})에 죽은 시험 JVM의 가드 스위치 행이 남아 있다:${harmless} — 그 신원은 다시 안 나오므로 빌드에 무해하다(지워도 된다)."
  fi
  if [[ -n "$blockers" ]]; then
    echo "[deploy] 시험 DB(${db})의 쓰기 가드 스위치 행이 빌드 시험의 보호 표 쓰기를 거절할 수 있다:${blockers} — 멈춘다." >&2
    echo "         살아 있는 시험이면 끝난 뒤 다시 친다. 「(전부)」·시작 초 없는 옛 꼴은 그 행을 지운 뒤 다시 친다(시험 DB · 사람이 본다)." >&2
    exit 1
  fi
  local failed=""
  while IFS= read -r v; do
    [[ -z "$v" ]] && continue
    # 불리언을 글자와 이으면 true/false로 나온다(t/f가 아니다 — 처음에 t와 비교해 모든 DB를 막았다 · 연습).
    if [[ "${v#*|}" != "true" ]]; then failed="${failed} V${v%%|*}"; fi
    v="${v%%|*}"
    if ! compgen -G "$ROOT/src/main/resources/db/migration/V${v}__*.sql" >/dev/null \
        && ! compgen -G "$ROOT/src/test/resources/db/testmigration/V${v}__*.sql" >/dev/null; then
      extra="${extra} V${v}"
    fi
  done <<< "$versions"
  if [[ -n "$failed" ]]; then
    echo "[deploy] 시험 DB(${db})에 실패한 마이그레이션 행이 있다:${failed} — 빌드가 Flyway 검증에서 멈춘다. 이름을 바꿔 보존하고 새로 만든 뒤 다시 친다." >&2
    exit 1
  fi
  if [[ -n "$extra" ]]; then
    echo "[deploy] 시험 DB(${db})에 이 트리에 없는 마이그레이션이 적용돼 있다:${extra} — 다른 브랜치 시험이 남긴 것이다." >&2
    echo "         빌드가 Flyway 검증에서 멈춘다. 그 DB를 이름을 바꿔 보존하고 같은 이름으로 새로 만든 뒤(또는 TEST_DB_URL을 깨끗한 DB로) 다시 친다." >&2
    echo "         (버전 유무만 본다 — 같은 버전의 내용이 달라진 체크섬 불일치는 못 잡는다. 빌드가 「checksum mismatch」로 멈추면 같은 처방이다.)" >&2
    exit 1
  fi
}
test_db_guard

# 운영 DB와 이 트리의 마이그레이션 순서를 빌드 전에 맞춘다 — 운영은 out-of-order가 꺼져 있어(시험 프로파일만 켬)
# 운영 최대보다 낮은 번호가 뒤늦게 오면 Flyway 검증이 기동을 세우고, 앱이 내려간 채 그날 크론이 빠진다. 보류 브랜치가
# 여럿(0부 V63 · 1부 V64 · 시계 · 잠금 · 2부)이라 계획에 적은 번호와 실제 배포 순서가 갈릴 수 있다(2026-10-07 반증:
# 뒤 번호를 먼저 적용한 사본에 앞 번호를 넣자 `Detected resolved migration not applied to database`). 시험은 out-of-order가
# 켜져 이것을 못 잡는다. 반대로 운영에 적용됐는데 이 트리에 없는 번호가 이 트리 최대보다 낮으면 「missing」으로 같은 검증이 선다.
prod_migration_guard() {
  local db="${DB_NAME:-stock_screener}" psql applied v max_applied=0 max_local=0 late="" missing=""
  psql="$(pg_bin psql)" || { echo "[deploy] psql을 못 찾아 운영 마이그레이션 순서를 판정하지 못했다 — 멈춘다." >&2; exit 1; }
  if ! applied="$(PGCONNECT_TIMEOUT=5 "$psql" -X -w -At -d "$db" -c "select version from flyway_schema_history where version is not null and success" 2>&1)"; then
    echo "[deploy] 운영 DB(${db})의 마이그레이션 이력을 읽지 못했다 — 멈춘다: ${applied}" >&2
    exit 1
  fi
  local -a locals=()
  local f
  for f in "$ROOT"/src/main/resources/db/migration/V*__*.sql; do
    v="${f##*/V}"; v="${v%%__*}"
    [[ "$v" =~ ^[0-9]+$ ]] || { echo "[deploy] 수가 아닌 마이그레이션 번호다: ${f##*/} — 순서를 판정하지 못했다. 멈춘다." >&2; exit 1; }
    locals+=("$v")
    (( v > max_local )) && max_local=$v
  done
  while IFS= read -r v; do
    [[ -z "$v" ]] && continue
    (( v > max_applied )) && max_applied=$v
  done <<< "$applied"
  for v in "${locals[@]}"; do
    if ! grep -qx "$v" <<< "$applied" && (( v < max_applied )); then late="${late} V${v}"; fi
  done
  while IFS= read -r v; do
    [[ -z "$v" ]] && continue
    if ! compgen -G "$ROOT/src/main/resources/db/migration/V${v}__*.sql" >/dev/null && (( v < max_local )); then
      missing="${missing} V${v}"
    fi
  done <<< "$applied"
  # 쓰기 가드가 켜진 운영에 잠금 기제가 없는 트리를 올리지 않는다(ADR-0175 · 잠금 결정 16). 그 jar는 신원도 잠금도 없이 쓰므로
  # 기동 시더부터 SSWG1로 거절돼 앱이 내려간 채가 된다. 되돌리기는 운영 guard_setting 행을 먼저 지우는 것이다(사용자 승인 · 원시 SQL).
  # 사정거리: 이 점검은 배포하는 트리 자신의 deploy.sh에 있다 — 잠금 전 커밋으로 되돌려 배포하면 그 트리의 옛 deploy.sh에는 이 점검이
  # 없으므로 되돌리기를 지키지 못한다. 실제 방어는 ADR-0175 §결과의 되돌리기 첫 단계다.
  # MUST: 표 존재는 따로 묻는다 — 같은 문장 안에서 guard_setting을 부르면 V66 전 운영 DB에서 파싱 단계에 죽어(relation does not
  # exist) 잠금 jar를 이 스크립트로 처음 배포할 수 없다.
  local guard_on="f" has_guard lock_migration
  if ! has_guard="$(PGCONNECT_TIMEOUT=5 "$psql" -X -w -At -d "$db" -c "select to_regclass('public.guard_setting') is not null" 2>&1)"; then
    echo "[deploy] 운영 DB(${db})의 쓰기 가드 스위치를 읽지 못했다 — 멈춘다: ${has_guard}" >&2
    exit 1
  fi
  if [[ "$has_guard" == "t" ]] && ! guard_on="$(PGCONNECT_TIMEOUT=5 "$psql" -X -w -At -d "$db" -c "select (count(*) > 0)::text from guard_setting where db_name = current_database()" 2>&1)"; then
    echo "[deploy] 운영 DB(${db})의 쓰기 가드 스위치를 읽지 못했다 — 멈춘다: ${guard_on}" >&2
    exit 1
  fi
  lock_migration="$(grep -l 'write_guard_check()' "$ROOT"/src/main/resources/db/migration/V*__*.sql 2>/dev/null | head -1 || true)"
  if [[ "$guard_on" == "true" && -z "$lock_migration" ]]; then
    echo "[deploy] 운영 DB(${db})는 쓰기 가드가 켜져 있는데 이 트리에 잠금 마이그레이션(write_guard_check)이 없다 — 이 jar는 기동에서 거절돼 앱이 내려간 채가 된다. 아무것도 바꾸지 않았다." >&2
    echo "         잠금 전 jar로 되돌리려면 먼저 운영 guard_setting 행을 지운다(사용자 승인 · ADR-0175 §결과 되돌리기)." >&2
    exit 1
  fi
  if [[ -n "$late" ]]; then
    echo "[deploy] 이 트리의 미적용 마이그레이션이 운영 최대(V${max_applied})보다 낮다:${late} — 운영은 out-of-order가 꺼져 있어 기동이 Flyway 검증에서 선다." >&2
    echo "         번호를 운영 최대보다 크게 다시 매긴 뒤(배포 직전 merge에서) 다시 친다. 아무것도 바꾸지 않았다." >&2
    exit 1
  fi
  if [[ -n "$missing" ]]; then
    echo "[deploy] 운영에 적용됐는데 이 트리에 없는 마이그레이션이 이 트리 최대(V${max_local})보다 낮다:${missing} — 기동이 「missing」으로 선다." >&2
    echo "         그 번호를 든 브랜치를 먼저 합친다. 아무것도 바꾸지 않았다." >&2
    exit 1
  fi
}
prod_migration_guard

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
lock_gate
load_gate
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
