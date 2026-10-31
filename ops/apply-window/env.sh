# 공통. 스크립트마다 source로 다시 읽는다(호출 사이에 셸 상태가 안 남는다).
# 미국 관문 실행기 — 순서와 근거는 README.md, 계획 정본은 브랜치 explore/s-vintage-coherence의 explore/financial-sync/GATE-RUN-PLAN-1016.md(v2).
# 옛 Q4V 적용 창 절차(전체 사본 둘)는 q4v/에 기록으로 남아 있다 — 이 파일은 그것을 쓰지 않는다.
# MUST: 잡은 하나씩 돌린다. 잡이 도는 중에 gradle 시험·컴파일을 띄우지 않는다 — devtools가 그 JVM을 재시작한다.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "$REPO/ops/lib.sh"
# 산출물 폴더. 저장소 밖에 둔다(TSV가 수백 MB다).
# MUST: 공백 없는 경로여야 한다 — 스크립트가 $A를 따옴표 없이 리다이렉션에 쓰는 자리가 있어 「ambiguous redirect」로 조용히 죽는다.
A="${GATE_WORK:-$HOME/stock-screener-gate-run}"
case "$A" in *" "*) echo "[멈춤] 산출물 경로에 공백이 있다: $A" >&2; exit 1;; esac
mkdir -p "$A"
LIVE=stock_screener              # 진짜 운영 DB 이름 — 이 이름일 때만 알림을 켠 채 돈다
PROD=${GATE_PROD:-$LIVE}         # 드라이런은 GATE_PROD=<작은 DB>로 운영 역할을 작은 DB에 준다(small-db.sh)
RETRYABLE='TRANSIENT RATE_LIMITED'
# 기대 실패 — 티커<TAB>종류<TAB>근거 한 줄. 드라이런·후보 스윕이 낸 것을 사람이 근거와 함께 옮긴다. 없으면 기대 실패 0이다.
EXPECTED="$A/expected-failures.tsv"
# 관문 설정이 명령줄에서 왔다는 앱의 말(GateSettingGuard). 이 줄이 없으면 그 실행은 관문 실행이 아니다.
GATE_LINE='관문 설정 stockscreener.gate.us=true (출처: commandLineArgs)'
GATE_ARGS='--stockscreener.gate.us=true'
q() { command psql -d "$1" -v ON_ERROR_STOP=1 -At -F $'\t' -c "$2"; }
die() { echo "[멈춤] $*" >&2; exit 1; }
abspath() { case "$1" in /*) echo "$1";; *) echo "$A/$1";; esac; }
head_now() { (cd "$REPO" && git rev-parse HEAD); }
# 그 DB의 쓰기 가드가 켜졌는가(ADR-0175 · guard_setting에 current_database() 행). 운영과 small-db.sh가 만든 작은 DB가 켜짐이다.
guard_on() { local s; s=$(write_guard_switch "$1") || die "$1 가드 스위치를 묻지 못했다"; case "$s" in 켜짐*) return 0;; *) return 1;; esac; }
# 앱이 떠 있으면 멈춘다 — 꺼진 jar의 크론(13:30 · 14:16 · 14:20 · 21:00)이 관문 도중에 옛 규칙으로 쓴다.
# 운영 DB이거나 GATE_REQUIRE_APP_DOWN=1일 때만 본다(작은 DB 드라이런은 운영 앱과 DB가 달라 끼어들지 않는다 · 대조군은 이 변수로 태운다).
app_down() {
  [ "$1" = "$LIVE" ] || [ "${GATE_REQUIRE_APP_DOWN:-0}" = 1 ] || return 0
  local pid; if pid=$(running_pid "$APP_PID_FILE" "$APP_PATTERN"); then die "앱이 떠 있다(pid $pid) — 00b-stop.sh부터"; fi
}
boot() { local db="$1"; shift
  [ "$(head_now)" = "$(cat "$A/head.txt")" ] || die "HEAD가 사전 점검 뒤 바뀌었다 — bootRun은 작업 트리를 컴파일해 쓴다"
  app_down "$db"
  local strip=()
  if guard_on "$db"; then
    # 가드가 켜진 DB는 쓰기 세션 안에서만 쓴다 — 세션 변수가 없으면 JVM이 잠금을 못 쥐고 잡이 거절된다.
    [ -n "${SS_WRITE_SESSION:-}" ] || die "${db}는 가드가 켜져 있다 — ops/write-session.sh exec <id> --db $db -- <이 스크립트>로 돈다"
  else
    # 가드가 꺼진 DB에 세션 변수를 물려주면 JVM이 남의 DB 세션에 합류하려 들어 기동이 선다.
    strip=(-u SS_WRITE_SESSION -u PGAPPNAME)
  fi
  if [ "$db" = "$LIVE" ]; then
    (cd "$REPO" && env ${strip[@]+"${strip[@]}"} ./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap $*")
  else
    # 작은 DB는 두 변수를 다 준다(DB_URL만 주면 BootstrapDatabaseGuard가 기동을 세워 종료코드 0으로 끝난다) ·
    # 알림 변수를 비운다(작은 DB의 무효화 알림이 운영 채널로 가지 않게).
    (cd "$REPO" && env ${strip[@]+"${strip[@]}"} DB_URL="jdbc:postgresql://localhost:5432/$db" BOOTSTRAP_DB="$db" \
      ALERT_WEBHOOK_URL= MAIL_USERNAME= SENTRY_DSN= ./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap $*")
  fi; }
# 관문 실행의 로그에 관문 설정 줄이 있는가. 인자를 빼먹은 실행은 꺼진 갈래로 돌아 V70 표를 안 쓰고도 성공한다.
gate_line() { grep -qF "$GATE_LINE" "$1" || die "$1에 「${GATE_LINE}」이 없다 — 관문 인자 없이 돌았다"; }
max_exec_id() { q "$1" "select coalesce(max(id),0) from batch_execution"; }
# 이번 실행 행 하나를 찾아 단언하고 그 id를 낸다. 가장 마지막 행이 아니라 「P 뒤 · 그 job · MANUAL」 정확히 1행이다.
new_exec() { # <db> <이전 max id> <job> <기대 target | ->
  local rows n id status target success failure
  rows=$(q "$1" "select id, status, target_count, success_count, failure_count from batch_execution where id > $2 and job_name = '$3' and triggered_by = 'MANUAL'")
  n=$(printf '%s' "$rows" | grep -c . || true); [ "$n" -eq 1 ] || die "$3 새 실행 행이 1이 아니다($n): $rows"
  IFS=$'\t' read -r id status target success failure <<< "$rows"
  case "$status" in SUCCESS|PARTIAL_SUCCESS) ;; *) die "$3 실행 상태 $status: $rows";; esac
  [ "$4" = - ] || [ "$target" -eq "$4" ] || die "$3 대상 수 $target ≠ 기대 $4"
  echo "$id"
}
failures_of() { q "$1" "select s.ticker || ':' || f.failure_kind from batch_execution_failure f join stock s on s.id=f.stock_id where f.execution_id=$2 order by 1"; }
# 기대 실패(티커:종류) 목록 — 파일이 없으면 빈 목록이다.
expected_list() { [ -f "$EXPECTED" ] && awk -F'\t' 'NF>=3 && $1 !~ /^#/ {print $1":"$2}' "$EXPECTED" || true; }
# financial-refill note의 계수 다섯(신규·정정·채움·교정·이른판)을 합산한다. MUST: 이 토큰 꼴(낱말=수)을 바꾸면 이 정규식이 0으로 읽는다 —
# FinancialRefillJob·MarketCompleteness의 javadoc과 FinancialRefillAlertTest·MarketCompletenessTest가 이 함수를 이름으로 가리킨다.
note_nums() { python3 -c "
import re,sys,collections
t=collections.Counter()
for l in open(sys.argv[1]):
    for k,v in re.findall(r'(신규|정정|채움|교정|이른판)=([0-9]+)', l): t[k]+=int(v)
print(' '.join(f'{k}={t[k]}' for k in ('신규','정정','채움','교정','이른판')))" "$1"; }
