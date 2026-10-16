# 공통. 스크립트마다 source로 다시 읽는다(호출 사이에 셸 상태가 안 남는다).
# 적재·백필 창의 실행 절차. 순서와 근거는 README.md, 결정의 정본은 docs/adr/ADR-0161-earlier-sec-vintages-are-stored.md.
# MUST: 잡은 하나씩 돌린다. 잡이 도는 중에 gradle 시험·컴파일을 띄우지 않는다 — devtools가 그 JVM을 재시작한다.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# 산출물 폴더. 저장소 밖에 둔다(TSV가 수백 MB다).
# MUST: 공백 없는 경로여야 한다 — 스크립트가 $A를 따옴표 없이 리다이렉션에 쓰는 자리가 있어
# 「ambiguous redirect」로 조용히 죽는다(2026-09-18 적용 창에서 실제로 났다).
A="${Q4V_WORK:-$HOME/stock-screener-apply-window}"
SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "$A" in *" "*) echo "[멈춤] 산출물 경로에 공백이 있다: $A" >&2; exit 1;; esac
mkdir -p "$A"
PGBIN=/opt/homebrew/opt/postgresql@16/bin
LIVE=stock_screener                       # 진짜 운영 DB 이름 — 이 이름일 때만 알림을 켠 채 돈다
PROD=${Q4V_PROD:-$LIVE}                   # 리허설은 Q4V_PROD=<사본>으로 운영 역할을 사본에 준다
S1=${Q4V_S1:-stock_screener_q4v_shadow}   # 플래그 참 — 끼어듦·명령 확인
S0=${Q4V_S0:-stock_screener_q4v_ctrl}     # 플래그 거짓 — 구간 규칙 대조군
EXPECTED_FAILURES='APGE:NOT_FOUND CRNX:NOT_FOUND'
RETRYABLE='TRANSIENT RATE_LIMITED'
q() { command psql -d "$1" -v ON_ERROR_STOP=1 -At -F $'\t' -c "$2"; }
die() { echo "[멈춤] $*" >&2; exit 1; }
abspath() { case "$1" in /*) echo "$1";; *) echo "$A/$1";; esac; }
head_now() { (cd "$REPO" && git rev-parse HEAD); }
boot() { local db="$1"; shift
  [ "$(head_now)" = "$(cat $A/head.txt)" ] || die "HEAD가 사전 점검 뒤 바뀌었다 — bootRun은 작업 트리를 컴파일해 쓴다"
  if [ "$db" = "$LIVE" ]; then (cd "$REPO" && ./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap $*")
  # 사본은 세션 변수를 걷는다(ADR-0175) — 쓰기 세션은 운영 DB의 잠금 행이라 사본에 붙은 JVM이 합류하려 들면 「세션이 쓰기 잠금을
  # 쥐고 있지 않다」로 기동이 선다. 사본은 이름이 달라 가드가 꺼져 있으므로 그 JVM은 사본의 잠금을 따로 쥔다.
  else (cd "$REPO" && env -u SS_WRITE_SESSION -u PGAPPNAME DB_URL="jdbc:postgresql://localhost:5432/$db" BOOTSTRAP_DB="$db" ALERT_WEBHOOK_URL= MAIL_USERNAME= SENTRY_DSN= ./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap $*"); fi; }
max_exec_id() { q "$1" "select coalesce(max(id),0) from batch_execution"; }
# 이번 실행 행 하나를 찾아 단언하고 그 id를 낸다. 가장 마지막 행이 아니라 「P 뒤 · 그 job · MANUAL」 정확히 1행이다.
new_exec() { # <db> <이전 max id> <job> <기대 target | ->
  local rows n id status target success failure
  rows=$(q "$1" "select id, status, target_count, success_count, failure_count from batch_execution where id > $2 and job_name = '$3' and triggered_by = 'MANUAL'")
  n=$(printf '%s' "$rows" | grep -c . || true); [ "$n" -eq 1 ] || die "$3 새 실행 행이 1이 아니다($n): $rows"
  IFS=$'\t' read -r id status target success failure <<< "$rows"
  case "$status" in SUCCESS|PARTIAL_SUCCESS) ;; *) die "$3 실행 상태 $status: $rows";; esac
  [ "$4" = - ] || [ "$target" -eq "$4" ] || die "$3 대상 수 $target ≠ 기대 $4"
  [ $((success + failure)) -eq "$target" ] || [ "$3" = indicator-backfill ] || die "$3 성공+실패 ≠ 대상: $rows"
  echo "$id"
}
failures_of() { q "$1" "select s.ticker || ':' || f.failure_kind from batch_execution_failure f join stock s on s.id=f.stock_id where f.execution_id=$2 order by 1"; }
