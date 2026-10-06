#!/usr/bin/env bash
# 지금 앱을 내려도(배포 · 손 쓰기 절차) 앱 크론이 빠지지 않는가 — 시계가 아니라 기록으로 답한다.
#
# 왜 있는가: 앱 크론은 기계가 잠든 만큼 늦게 돈다(2026-10-06: 13:30 크론이 15:24:51에 돌았다 ·
# docs/reload-gate.md 「앱 크론이 기계 수면만큼 밀린다」). 밀린 발화가 남은 채 앱을 내리면 새 JVM은 다음 발화를
# 지금 뒤로 잡아 그날 남은 발화가 통째로 빠진다. 그래서 「15:40 뒤」 같은 벽시계 창은 그날 방어가 안 된다.
#
# MUST: 크론마다 묻는다. 밀림은 크론마다 다르다 — Spring은 크론마다 자기 본체가 끝난 순간에 다음 지연을 단조 시계로
# 고정하므로, 화해가 끝난 뒤 15:30 점검 전에 잠들면 다음 날 13:30~14:20은 밀리고 15:30 점검만 정시에 돈다
# (2026-10-06 반증 · 실제 Spring 스케줄러를 가상 시계로 돌린 재현). 「하루를 닫는 발화 하나」로 물으면 그날 거짓 0이다.
#
# 판정 — 전부 참이면 0:
#   1. 앱이 안 떠 있으면 빠질 발화가 없다(0).
#   2. 크론마다 지금까지 지난 마지막 명목 시각 t에 대해: 이 JVM이 t 뒤에 떴다(그 발화는 이 JVM에 없다) — 또는 그 크론의
#      끝 줄(「크론 발화를 마쳤다. 크론=<메서드>」 · PipelineScheduler의 finally)을 이 JVM이 t − 60초 뒤 시각으로 찍었다.
#      60초를 빼는 것은 크론이 명목보다 조금 일찍 발화하기 때문이다(운영 기록 13:30·14:00 첫 잡 62건 중 21건이 명목 전 ·
#      최대 −2.189초) — 할 일이 없어 바로 끝나는 크론은 끝 줄도 명목 전에 찍힌다. 같은 크론의 앞 발화는 하루 전이라 안 겹친다.
#      끝 줄은 회전된 로그(stdout.log.N.gz)까지 찾는다.
#   3. batch_execution에 앱이 가진(SCHEDULER · ADMIN · GAP_FILL) RUNNING이 없다 — MANUAL은 다른 JVM(bootRun)이라 앱을 내려도
#      안 끊기고, 스위퍼가 다음 bootstrap 때에야 닫는 유령 행(08-06~09-08에 12건 · 최대 76시간)이 판정을 막는다.
# 지연 L(벽시계 경과 − JVM 가동 시간)은 참고로만 찍는다 — 크론마다의 밀림이 아니다.
# 다음 명목 발화 시각을 함께 찍는다 — 앱을 내렸으면 그 전에 다시 올린다.
#
#   5. 다음 명목 발화가 5분 넘게 남았다 — 재기동 창(실측 약 4초)이 명목 시각에 걸치면 새 JVM이 그 발화를 다음 날로 잡는다.
# 0의 뜻은 「지금 내려도 빠질 발화가 없다」다 — 「그날 크론이 다 끝났다」가 아니다(오전 · 리밸런싱 전 토요일에도 0이다).
# 「그날 무엇이 돌았나」를 물을 때는 batch_execution과 끝 줄을 따로 본다.
#
# 종료코드: 0 내려도 된다 · 1 아직 안 된다 · 2 판정하지 못했다(psql 실패).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=ops/lib.sh
. "$HERE/lib.sh"

# 크론 표 — PipelineScheduler의 @Scheduled와 같아야 한다(CronsDoneTableTest가 묻는다). <메서드> <요일 1=월…7=일> <명목 시각>
CRONS=(
  "runDailyUpdate 1-5 13:30:00"
  "runCorporateActionChain 1-5 14:00:00"
  "runHeavyJobOfTheDay 1-5 14:15:00"
  "deriveReconcileInputs 1-5 14:18:00"
  "reconcileIndicators 1-5 14:20:00"
  "checkJobFreshness 1-5 15:30:00"
  "runUniverseRebalance 6-6 20:00:00"
)

DB="${DB_NAME:-stock_screener}"
now=$(date +%s)
epoch() { date -j -f "%Y-%m-%d %H:%M:%S" "$1" +%s; }
runs_on() { local dow="$1" span="$2"; [ "$dow" -ge "${span%-*}" ] && [ "$dow" -le "${span#*-}" ]; }

# 크론 하나의 마지막 명목 시각(지금 이전) · 다음 명목 시각(지금 뒤).
last_nominal() {
  local span="$1" t="$2" d
  for i in 0 1 2 3 4 5 6 7; do
    d=$(date -j -v-"${i}"d +%F)
    if runs_on "$(date -j -f %F "$d" +%u)" "$span" && [ "$(epoch "$d $t")" -le "$now" ]; then echo "$d $t"; return; fi
  done
}
next_nominal() {
  local span="$1" t="$2" d
  for i in 0 1 2 3 4 5 6 7; do
    d=$(date -j -v+"${i}"d +%F)
    if runs_on "$(date -j -f %F "$d" +%u)" "$span" && [ "$(epoch "$d $t")" -gt "$now" ]; then echo "$d $t"; return; fi
  done
}
next_fire() {
  local best="" n
  for c in "${CRONS[@]}"; do
    read -r _ span t <<< "$c"
    n=$(next_nominal "$span" "$t")
    if [ -z "$best" ] || [ "$(epoch "$n")" -lt "$(epoch "$best")" ]; then best="$n"; fi
  done
  echo "$best"
}

if ! pid=$(running_pid "$APP_PID_FILE" "$APP_PATTERN"); then
  echo "[crons] 앱이 안 떠 있다 — 빠질 발화가 없다. 다음 명목 발화: $(next_fire)"
  exit 0
fi

etime=$(ps -o etime= -p "$pid" | tr -d ' ')
IFS=- read -r days rest <<< "$( [[ "$etime" == *-* ]] && echo "$etime" || echo "0-$etime" )"
IFS=: read -r -a hms <<< "$rest"
elapsed=0
for part in "${hms[@]}"; do elapsed=$((elapsed * 60 + 10#$part)); done
elapsed=$((elapsed + days * 86400))
jvm_start=$((now - elapsed))
if uptime_line=$(jcmd "$pid" VM.uptime 2>/dev/null | tail -1) && up="${uptime_line%% *}" && up="${up%%.*}" \
    && [[ "$up" =~ ^[0-9]+$ ]]; then
  L="$((elapsed - up))초"
else
  L="못 읽음(jcmd)"
fi
echo "[crons] 앱 PID $pid · 뜬 시각 $(date -r "$jvm_start" '+%F %T') · 지연 L=${L}(참고)"

logs=("$LOG_DIR/stdout.log")
for f in "$LOG_DIR"/stdout.log.*.gz; do [ -e "$f" ] && logs+=("$f"); done

pending=0
for c in "${CRONS[@]}"; do
  read -r name span t <<< "$c"
  last=$(last_nominal "$span" "$t")
  [ -n "$last" ] || continue
  last_s=$(epoch "$last")
  if [ "$jvm_start" -ge "$last_s" ]; then
    continue
  fi
  end=$(zgrep -a -h " INFO $pid --- .*크론 발화를 마쳤다. 크론=${name}\$" "${logs[@]}" 2>/dev/null | cut -c1-19 | sort | tail -1 || true)
  if [ -n "$end" ] && [ "$(date -j -f "%Y-%m-%dT%H:%M:%S" "$end" +%s)" -ge "$((last_s - 60))" ]; then
    continue
  fi
  echo "[crons] ${name}: 명목 ${last} 발화의 끝 줄이 아직 없다 — 밀렸거나 도는 중이다(이 JVM의 마지막 끝 줄: ${end:-없음})."
  pending=$((pending + 1))
done
if [ "$pending" -gt 0 ]; then
  if ! zgrep -a -q " INFO $pid --- .*크론 발화를 마쳤다" "${logs[@]}" 2>/dev/null; then
    echo "[crons] 이 JVM의 끝 줄이 아직 하나도 없다 — 기동 뒤 첫 발화가 안 끝났거나, 끝 줄을 찍기 전 jar다(배포 폴더의"
    echo "        app.jar.commit 커밋에 PipelineScheduler의 「크론 발화를 마쳤다」가 있는지로 가른다)."
  fi
  echo "[crons] 남은 발화 ${pending}개 — 아직 안 된다."
  exit 1
fi

psql=$(pg_bin psql) || { echo "[crons] psql을 못 찾았다 — 판정하지 못했다." >&2; exit 2; }
if ! running=$(PGCONNECT_TIMEOUT=5 "$psql" -X -w -At -d "$DB" -c "select count(*) from batch_execution where status='RUNNING' and triggered_by in ('SCHEDULER','ADMIN','GAP_FILL')" 2>/dev/null); then
  echo "[crons] batch_execution을 못 읽었다 — 판정하지 못했다." >&2
  exit 2
fi
if [ "$running" -ne 0 ]; then
  echo "[crons] 앱이 돌리는 배치가 ${running}개다 — 아직 안 된다."
  exit 1
fi
next=$(next_fire)
left=$(( $(epoch "$next") - now ))
if [ "$left" -lt 300 ]; then
  echo "[crons] 다음 명목 발화(${next})가 $((left / 60))분 $((left % 60))초 뒤다 — 재기동 창이 걸칠 수 있다. 지나간 뒤 다시 묻는다."
  exit 1
fi
echo "[crons] 내려도 된다. 다음 명목 발화: ${next}($((left / 60))분 뒤) — 그 전에 다시 올린다."
