#!/usr/bin/env bash
# 지금 앱을 내려도(배포 · 손 쓰기 절차) 앱 크론이 빠지지 않는가 — 시계가 아니라 기록으로 답한다.
#
# 왜 있는가: 앱 크론은 기계가 잠든 만큼 늦게 돈다(2026-10-06: 13:30 크론이 15:24:51에 돌았다 ·
# docs/reload-gate.md 「앱 크론이 기계 수면만큼 밀린다」). 밀린 발화가 남은 채 앱을 내리면 새 JVM은 다음 발화를
# 지금 뒤로 잡아 그날 남은 발화가 통째로 빠진다. 그래서 「15:40 뒤」 같은 벽시계 창은 그날 방어가 안 된다.
#
# 판정 — 전부 참이면 0:
#   1. 앱이 안 떠 있으면 빠질 발화가 없다(0).
#   2. 지연 L(벽시계 경과 − JVM 가동 시간) < 하루 — 넘으면 어느 날의 발화인지 못 가른다.
#   3. 지금까지 지난 명목 시각 중 마지막 「하루를 닫는 발화」 T(평일 15:30 점검 · 토 20:00 리밸런싱 꼬리)의 끝 줄을
#      이 JVM이 T 뒤 시각으로 찍었다 — 또는 이 JVM이 T 뒤에 떴다(그 발화는 이 JVM에 없다).
#      평일 13:30 ≤ 지금인데 T가 그 앞이면 오늘 사슬이 아직 안 닫혔다.
#      「오늘 날짜의 행이 있다」로 묻지 않는다 — 전날 발화가 자정을 넘겨 밀리면 다음 날 아침에 참이 된다.
#      화해 행으로도 묻지 않는다 — 14:00 사슬이 화해를 부르면 14:15에 이미 생기고, 화해 ①이 실패하면 ② 행이 안 생긴다.
#   4. batch_execution에 RUNNING이 없다.
# 다음 명목 발화 시각을 함께 찍는다 — 앱을 내렸으면 그 전에 다시 올린다.
#
# 종료코드: 0 내려도 된다 · 1 아직 안 된다 · 2 판정하지 못했다(jcmd · psql 실패).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=ops/lib.sh
. "$HERE/lib.sh"

DB="${DB_NAME:-stock_screener}"
now=$(date +%s)
epoch() { date -j -f "%Y-%m-%d %H:%M:%S" "$1" +%s; }

# 다음 명목 발화(평일 13:30 · 토 20:00) — 지금 뒤로 가장 이른 것.
next_fire() {
  local d dow t
  for i in 0 1 2 3 4 5 6 7; do
    d=$(date -j -v+"${i}"d +%F)
    dow=$(date -j -f %F "$d" +%u)
    for t in "13:30:00" "20:00:00"; do
      if { [ "$t" = "13:30:00" ] && [ "$dow" -le 5 ]; } || { [ "$t" = "20:00:00" ] && [ "$dow" -eq 6 ]; }; then
        if [ "$(epoch "$d $t")" -gt "$now" ]; then echo "$d $t"; return; fi
      fi
    done
  done
}

if ! pid=$(running_pid "$APP_PID_FILE" "$APP_PATTERN"); then
  echo "[crons] 앱이 안 떠 있다 — 빠질 발화가 없다. 다음 명목 발화: $(next_fire)"
  exit 0
fi

# 2. 지연 L
etime=$(ps -o etime= -p "$pid" | tr -d ' ')
IFS=- read -r days rest <<< "$( [[ "$etime" == *-* ]] && echo "$etime" || echo "0-$etime" )"
IFS=: read -r -a hms <<< "$rest"
elapsed=0
for part in "${hms[@]}"; do elapsed=$((elapsed * 60 + 10#$part)); done
elapsed=$((elapsed + days * 86400))
jvm_start=$((now - elapsed))
if ! uptime_line=$(jcmd "$pid" VM.uptime 2>/dev/null | tail -1); then
  echo "[crons] jcmd로 JVM 가동 시간을 못 읽었다(PID $pid) — 판정하지 못했다." >&2
  exit 2
fi
uptime_s=${uptime_line%%.*}
L=$((elapsed - uptime_s))
echo "[crons] 앱 PID $pid · 뜬 시각 $(date -r "$jvm_start" '+%F %T') · 지연 L=${L}초"
if [ "$L" -ge 86400 ]; then
  echo "[crons] 지연이 하루를 넘는다 — 어느 날의 발화인지 못 가른다. 아직 안 된다." >&2
  exit 1
fi

# 3. 마지막 「하루를 닫는 발화」 T와 그 끝 줄
T=""; marker=""
for i in 0 1 2 3 4 5 6 7; do
  d=$(date -j -v-"${i}"d +%F)
  dow=$(date -j -f %F "$d" +%u)
  if [ "$dow" -le 5 ]; then cand="$d 15:30:00"; m="편입 원자료를 점검했다"
  elif [ "$dow" -eq 6 ]; then cand="$d 20:00:00"; m="편입 결손을 다시 쟀다"
  else continue; fi
  if [ "$(epoch "$cand")" -le "$now" ]; then T="$cand"; marker="$m"; break; fi
done
T_s=$(epoch "$T")
today=$(date +%F)
if [ "$(date +%u)" -le 5 ] && [ "$(epoch "$today 13:30:00")" -le "$now" ] && [ "$T_s" -lt "$(epoch "$today 13:30:00")" ]; then
  echo "[crons] 오늘 사슬(13:30~15:30)이 아직 안 닫혔다 — 오늘 15:30 점검의 끝 줄까지 기다린다(지연 L이면 명목 + ${L}초)."
  exit 1
fi
if [ "$jvm_start" -ge "$T_s" ]; then
  echo "[crons] 이 JVM은 $T 뒤에 떴다 — 그 발화는 이 JVM에 없다."
else
  last=$(grep -a " INFO $pid --- " "$LOG_DIR/stdout.log" | grep -a "$marker" | tail -1 | cut -c1-19 || true)
  if [ -z "$last" ] || [ "$(date -j -f "%Y-%m-%dT%H:%M:%S" "$last" +%s)" -lt "$T_s" ]; then
    echo "[crons] $T 발화의 끝 줄(「${marker}」)이 아직 없다 — 밀렸거나 돌다 끊겼다(마지막 줄: ${last:-없음}). 아직 안 된다."
    exit 1
  fi
  echo "[crons] $T 발화가 ${last}에 끝났다."
fi

# 4. RUNNING
psql=$(pg_bin psql) || { echo "[crons] psql을 못 찾았다 — 판정하지 못했다." >&2; exit 2; }
if ! running=$("$psql" -X -At -d "$DB" -c "select count(*) from batch_execution where status='RUNNING'" 2>/dev/null); then
  echo "[crons] batch_execution을 못 읽었다 — 판정하지 못했다." >&2
  exit 2
fi
if [ "$running" -ne 0 ]; then
  echo "[crons] 도는 배치가 ${running}개다 — 아직 안 된다."
  exit 1
fi
echo "[crons] 내려도 된다. 다음 명목 발화: $(next_fire) — 그 전에 다시 올린다."
