#!/bin/bash
#
# 손 쓰기 세션 — 사람이 운영 DB에 쓰는 일(psql · pg_restore · bootRun 연쇄)을 쓰기 잠금 안에서 한다(ADR-0175).
#
#   ops/write-session.sh begin  <id> [--db <이름>]              잠금을 세션으로 쥔다
#   ops/write-session.sh exec   <id> [--db <이름>] -- <명령…>    그 명령을 세션 신원으로 돌린다
#   ops/write-session.sh end    <id> [--db <이름>]              반납한다
#   ops/write-session.sh status      [--db <이름>]              지금 보유자
#
# <id>는 [a-z0-9-]{1,24}다 — 연결의 application_name이 63바이트에서 조용히 잘리고 비ASCII는 이스케이프된다(잠금 v3).
# --db를 안 주면 DB_NAME(없으면 stock_screener)이다. 잠금 행은 DB마다 하나라 세션도 그 DB의 것이다.
#
# **exec가 하는 일**: 그 명령(과 그 자식)에만 SS_WRITE_SESSION=<id>와 PGAPPNAME=ss:s:<id>를 준다. bootRun은 앞의 것을 읽어
# 세션에 합류하고(ss:s:<id>:j:<pid>:<시작초>), psql·pg_restore는 뒤의 것으로 세션 신원이 된다. 인자에 덧붙이지 않는 이유는
# bootRun 앱 인자가 --args="…" 문자열 안이라 덧붙일 자리가 없어서다(실측: Unknown command-line option). 실행 동안 이 래퍼
# 자신(pid · 시작 초)이 세션 구성원이라 긴 exec 도중에 세션이 만료되지 않는다(잠금 v4 반증 L1-2).
#
# **세션 만료** = 산 구성원 0 ∧ 마지막 활동(begin · exec 시작·끝)에서 60분. 만료된 세션은 다른 쓰기가 넘겨받을 수 있고, 그 뒤의
# exec는 거절된다 — 그 사이 남의 쓰기가 끼었을 수 있으므로 begin부터 다시 한다.
#
# MUST NOT: 세션 안에서 ops/deploy.sh·ops/start.sh를 exec하지 않는다 — 앱 JVM이 세션 변수를 물려받는다(start.sh가 env -u로
# 걷고 웹 프로파일은 그 변수를 보면 기동을 세운다). 절차 중간에 배포가 끼면 end → deploy → begin이다.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "$HERE/lib.sh"

usage() {
  sed -n '4,8p' "${BASH_SOURCE[0]}" | sed 's/^# //' >&2
  exit 2
}

die() {
  echo "[write-session] $*" >&2
  exit 1
}

[[ $# -ge 1 ]] || usage
ACTION="$1"; shift
ID=""
if [[ "$ACTION" != "status" ]]; then
  [[ $# -ge 1 ]] || usage
  ID="$1"; shift
  [[ "$ID" =~ ^[a-z0-9-]{1,24}$ ]] || die "세션 id 「${ID}」의 꼴이 틀렸다 — [a-z0-9-]{1,24}만 쓴다."
fi
DB="${DB_NAME:-stock_screener}"
if [[ "${1:-}" == "--db" ]]; then
  [[ $# -ge 2 ]] || usage
  DB="$2"; shift 2
fi

PSQL="$(pg_bin psql)" || die "psql을 찾을 수 없다."

# q <sql> — 잠금 표를 묻는다. 실패는 그대로 실패다(조용히 「없음」으로 접지 않는다).
q() {
  PGCONNECT_TIMEOUT=5 PGAPPNAME="ss:s:${ID:-ops}" "$PSQL" -w -X -q -v ON_ERROR_STOP=1 -d "$DB" -tAF'|' -c "$1"
}

require_table() {
  [[ "$(q "SELECT to_regclass('public.write_lock') IS NOT NULL")" == "t" ]] \
    || die "DB ${DB}에 write_lock 표가 없다(V66 전) — 잠금 jar를 먼저 배포한다."
}

# read_row — 전역 변수에 잠금 행을 싣는다. 행이 없으면 VERSION이 빈다.
read_row() {
  local row
  row="$(q "SELECT coalesce(l.version::text, ''), coalesce(holder_kind, ''), coalesce(holder_pid::text, ''),
                   coalesce(holder_start::text, ''), coalesce(session_id, ''), coalesce(array_to_string(members, ','), ''),
                   coalesce((extract(epoch FROM now() - coalesce(last_activity_at, acquired_at)) / 60)::bigint::text, '0'),
                   coalesce(waiter_pid::text, ''), coalesce(waiter_start::text, ''), coalesce(waiter_purpose, ''),
                   coalesce(purpose, '')
              FROM (SELECT 1) one LEFT JOIN write_lock l ON l.db_name = current_database()")"
  IFS='|' read -r VERSION KIND HOLDER_PID HOLDER_START SESSION MEMBERS IDLE WAITER_PID WAITER_START WAITER_PURPOSE PURPOSE <<< "$row"
}

live_members() {
  local member alive=""
  IFS=',' read -ra list <<< "$MEMBERS"
  for member in "${list[@]:-}"; do
    if [[ -n "$member" ]] && lock_member_alive "$member"; then
      alive="${alive:+$alive,}$member"
    fi
  done
  echo "$alive"
}

session_expired() {
  [[ -z "$(live_members)" && "$IDLE" -ge "$WRITE_SESSION_TTL_MINUTES" ]]
}

describe_holder() {
  case "$KIND" in
    "")      echo "없음" ;;
    SESSION) echo "손 세션 「${SESSION}」(구성원 ${MEMBERS:-없음} · 유휴 ${IDLE}분)" ;;
    *)       echo "$KIND pid $HOLDER_PID 「${PURPOSE}」" ;;
  esac
}

# 비었거나 넘겨받을 수 있으면 그 사유(빈 문자열 = 비었음)를 찍고 0. 산 보유자면 1.
freed_because() {
  if [[ -z "$KIND" ]]; then
    echo ""
    return 0
  fi
  if [[ "$KIND" == "SESSION" ]]; then
    if session_expired; then
      echo "세션 「${SESSION}」 만료(산 구성원 0 · 유휴 ${IDLE}분)"
      return 0
    fi
    return 1
  fi
  if proc_alive "$HOLDER_PID" "$HOLDER_START"; then
    return 1
  fi
  echo "보유 $KIND pid ${HOLDER_PID}가 죽었다"
  return 0
}

begin() {
  require_table
  local attempt reason won
  for attempt in 1 2 3 4 5; do
    read_row
    if [[ "$KIND" == "SESSION" && "$SESSION" == "$ID" ]] && ! session_expired; then
      echo "[write-session] 세션 「${ID}」은 이미 열려 있다(DB $DB)."
      return 0
    fi
    if ! reason="$(freed_because)"; then
      die "쓰기 잠금을 $(describe_holder)이(가) 쥐고 있다(DB $DB) — 끝난 뒤 다시 begin한다."
    fi
    if [[ -n "$WAITER_PID" ]] && proc_alive "$WAITER_PID" "$WAITER_START"; then
      die "쓰기 크론($WAITER_PURPOSE)이 잠금 차례를 기다린다 — 그 크론이 먼저 돈다. 끝난 뒤 다시 begin한다."
    fi
    won="$(q "INSERT INTO write_lock AS l
                  (db_name, version, holder_kind, token, purpose, session_id, holder_pid, holder_start, members,
                   acquired_at, heartbeat_at, last_activity_at, waiter_pid, waiter_start, waiter_purpose, waiter_since)
              VALUES (current_database(), 1, 'SESSION', gen_random_uuid(), 'session:$ID', '$ID', NULL, NULL, '{}',
                      now(), now(), now(), NULL, NULL, NULL, NULL)
              ON CONFLICT (db_name) DO UPDATE SET
                  version = l.version + 1, holder_kind = 'SESSION', token = EXCLUDED.token, purpose = EXCLUDED.purpose,
                  session_id = EXCLUDED.session_id, holder_pid = NULL, holder_start = NULL, members = '{}',
                  acquired_at = now(), heartbeat_at = now(), last_activity_at = now(),
                  waiter_pid = NULL, waiter_start = NULL, waiter_purpose = NULL, waiter_since = NULL
              WHERE l.version = ${VERSION:-NULL}
              RETURNING 1")"
    if [[ "$won" == "1" ]]; then
      [[ -n "$reason" ]] && echo "[write-session] 넘겨받았다 — $reason." >&2
      echo "[write-session] 세션 「${ID}」을 열었다(DB $DB). 쓰기는 ops/write-session.sh exec $ID -- <명령>으로 한다."
      return 0
    fi
  done
  die "begin CAS가 5번 연달아 졌다 — 누군가 계속 잠금 행을 바꾸고 있다."
}

exec_in_session() {
  [[ "${1:-}" == "--" ]] || usage
  shift
  [[ $# -ge 1 ]] || usage
  require_table
  local me_start member attempt won rc
  me_start="$(proc_start_epoch $$)" || die "이 래퍼의 시작 시각을 못 읽었다."
  member="exec:$$:$me_start"
  for attempt in 1 2 3 4 5; do
    read_row
    [[ "$KIND" == "SESSION" && "$SESSION" == "$ID" ]] \
      || die "세션 「${ID}」이 쓰기 잠금을 쥐고 있지 않다(지금: $(describe_holder)) — begin부터 한다."
    if session_expired; then
      die "세션 「${ID}」이 만료됐다(유휴 ${IDLE}분 ≥ ${WRITE_SESSION_TTL_MINUTES}분) — 그 사이 남의 쓰기가 끼었을 수 있으니 begin부터 다시 한다."
    fi
    won="$(q "UPDATE write_lock SET version = version + 1, members = array_append(members, '$member'),
                     last_activity_at = now()
               WHERE db_name = current_database() AND version = $VERSION AND session_id = '$ID'
              RETURNING 1")"
    [[ "$won" == "1" ]] && break
  done
  [[ "$won" == "1" ]] || die "exec 등록 CAS가 5번 연달아 졌다."

  # 래퍼가 어떻게 끝나든 구성원에서 뺀다 — kill -9만 못 막고, 그때는 죽은 구성원이라 사망 술어가 걷는다.
  leave() {
    q "UPDATE write_lock SET version = version + 1, members = array_remove(members, '$member'),
              last_activity_at = now()
        WHERE db_name = current_database() AND session_id = '$ID'" >/dev/null \
      || echo "[write-session] 구성원 ${member}를 못 뺐다 — 래퍼가 죽은 뒤엔 사망 술어가 걷는다." >&2
  }
  trap leave EXIT

  set +e
  env SS_WRITE_SESSION="$ID" PGAPPNAME="ss:s:$ID" "$@"
  rc=$?
  set -e
  exit "$rc"
}

end_session() {
  require_table
  local attempt alive won
  for attempt in 1 2 3 4 5; do
    read_row
    [[ "$KIND" == "SESSION" && "$SESSION" == "$ID" ]] \
      || die "세션 「${ID}」이 열려 있지 않다(지금: $(describe_holder))."
    alive="$(live_members)"
    [[ -z "$alive" ]] || die "세션 「${ID}」에 산 구성원이 있다($alive) — 그것이 끝난 뒤 end한다."
    won="$(q "UPDATE write_lock SET version = version + 1, holder_kind = NULL, token = NULL, purpose = NULL,
                     session_id = NULL, holder_pid = NULL, holder_start = NULL, members = '{}', acquired_at = NULL,
                     heartbeat_at = NULL, last_activity_at = now()
               WHERE db_name = current_database() AND version = $VERSION AND session_id = '$ID'
              RETURNING 1")"
    if [[ "$won" == "1" ]]; then
      echo "[write-session] 세션 「${ID}」을 닫았다(DB $DB)."
      return 0
    fi
  done
  die "end CAS가 5번 연달아 졌다."
}

case "$ACTION" in
  begin)  begin ;;
  exec)   exec_in_session "$@" ;;
  end)    end_session ;;
  status) echo "[write-session] DB $DB 쓰기 잠금: $(write_lock_holder "$DB" || echo '읽지 못했다')" ;;
  *)      usage ;;
esac
