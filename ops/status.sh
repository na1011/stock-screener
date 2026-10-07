#!/bin/bash
#
# 앱·터널이 도는지, 밖에서 보이는지 한 번에 본다. 아무것도 바꾸지 않는다.
#
# **로컬 health만 보면 안 된다** — cloudflared가 죽어 있어도 초록이다. 밖에서 502면
# 터널은 살아 있고 앱이 죽은 것이고, 응답이 아예 없으면 터널이 죽은 것이다(ADR-0033).

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "$HERE/lib.sh"

if pid="$(running_pid "$APP_PID_FILE" "$APP_PATTERN")"; then
  echo "앱      : 돈다 (PID $pid)"
else
  echo "앱      : 안 돈다"
fi

if pid="$(running_pid "$TUNNEL_PID_FILE" "$TUNNEL_PATTERN")"; then
  echo "터널    : 돈다 (PID $pid)"
elif pgrep -f "$TUNNEL_PATTERN" >/dev/null 2>&1; then
  echo "터널    : 돈다 — 그런데 이 스크립트가 띄운 것이 아니다 (PID $(pgrep -f "$TUNNEL_PATTERN" | tr '\n' ' '))"
  echo "          부팅 시 뜨는 데몬이 남아 있는지 본다: launchctl print system/com.cloudflare.cloudflared"
else
  echo "터널    : 안 돈다"
fi

# **DB를 독립 항목으로 찍는다.** 아래 "덤프" 절도 psql로 붙지만 그것은 마이그레이션 대조의
# 부산물이라, DB가 통째로 없는 것과 덤프가 없는 것이 같은 줄로 보인다. 앱이 전적으로 DB에
# 의존하므로(없으면 Flyway에서 2초 만에 죽는다) 무엇이 막고 있는지를 여기서 이름으로 댄다.
echo -n "DB      : "
if pg_isready_bin="$(pg_bin pg_isready 2>/dev/null)"; then
  # MUST: `-d`를 준다. 없으면 libpq가 사용자명을 DB 이름으로 써서 서버가 매 호출마다
  # `FATAL: database "na1011" does not exist`를 남긴다. 종료코드는 안 바뀌고 로그만 더러워지는데,
  # 그 노이즈가 진짜 FATAL과 섞여 사고 진단을 방해한다(2026-09-26에 실제로 그랬다).
  "$pg_isready_bin" -h localhost -p 5432 -d "${DB_NAME:-stock_screener}" -q && db_rc=0 || db_rc=$?
  case "$db_rc" in
    0) echo "돈다 (localhost:5432)" ;;
    1) echo "기동·복구 중 — 아직 접속을 안 받는다" ;;
    *) echo "응답 없음 — postmaster 락 판정: $(classify_postmaster_lock "$PG_DATADIR/postmaster.pid")" ;;
  esac
else
  echo "판정 못 함 — pg_isready를 찾지 못했다"
fi

# 쓰기 잠금 보유자(ADR-0175). 표가 없으면(V66 전) 「표 없음」 · 못 물었으면 그렇다고 찍는다 — 「없음」과 갈라야 한다.
echo -n "쓰기잠금: "
write_lock_holder "${DB_NAME:-stock_screener}" || echo "판정하지 못했다(psql) — 없음으로 읽지 않는다"

echo -n ":8080   : "
lsof -nP -iTCP:8080 -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $1" (PID "$2")"}' | paste -sd' ' - | grep . || echo "비어 있다"

echo -n "로컬    : "
curl -s --max-time 3 "$HEALTH_LOCAL" || echo "응답 없음"
echo

echo -n "외부    : "
curl -s --max-time 10 "$HEALTH_PUBLIC" || echo "응답 없음"
echo

# 빌드가 남긴 Gradle 데몬. **운영과는 범위가 다르다** — 앱이 아니라 ./gradlew가 띄우는
# 것이고, 그래서 stop.sh가 건드리지 않는다(건드리면 다른 프로젝트 빌드까지 죽는다).
# 그래도 여기서 세는 이유는 "뭐가 돌고 있나"를 묻는 곳이 여기 하나여야 하기 때문이다.
gradle_n="$(pgrep -f 'GradleDaemon|GradleWorkerMain' 2>/dev/null | grep -c . || true)"
if [[ "${gradle_n:-0}" -gt 0 ]]; then
  echo "빌드데몬: ${gradle_n}개 — 운영과 무관하다. 메모리가 아까우면: ./gradlew --stop"
else
  echo "빌드데몬: 없다"
fi

# 자동 기동이 되살아나 있으면 여기서 보여야 한다. ADR-0043의 premise와 같은 것을 본다.
found=""
[[ -f "$HOME/Library/LaunchAgents/com.stockscreener.app.plist" ]] && found="$found app-LaunchAgent"
[[ -f "/Library/LaunchDaemons/com.cloudflare.cloudflared.plist" ]] && found="$found cloudflared-LaunchDaemon"
[[ -n "$(crontab -l 2>/dev/null)" ]] && found="$found crontab"
if [[ -n "$found" ]]; then
  echo "자동기동: 되살아났다 —$found  (ADR-0043은 0건을 전제한다)"
else
  echo "자동기동: 없다 (정상 — 앱·터널 기준. DB는 아래 별도)"
fi

# **DB는 ADR-0043이 걷은 대상이 아니다** — 그 ADR은 앱의 LaunchAgent와 cloudflared의
# LaunchDaemon을 해제했고 postgres는 brew가 관리하는 채로 남았다. 그런데 위 줄이 지위가
# 다른 것을 "등록이 하나도 없다"로 뭉쳐, **사고 당일 10초마다 89회 재시도한 주체가
# 여기 안 보였다**(2026-09-26). 갈라 찍는다 — 켜져 있는 것이 정상이다.
if [[ -f "$HOME/Library/LaunchAgents/sh.brew.postgresql@16.plist" ]]; then
  echo "DB자동기동: sh.brew.postgresql@16 (정상 — 부팅 시 뜨고, 죽으면 launchd가 되살린다)"
else
  echo "DB자동기동: 없다 — 부팅 뒤 DB를 손으로 띄워야 한다: brew services start postgresql@16"
fi

# 저장된 덤프가 지금 쓸 만한가. **아무것도 바꾸지 않는다 — 세고 찍기만 한다.**
#
# `guard-rebuild-without-dump.sh`는 덤프 **존재**만 보고 통과하며 통과할 때 침묵한다.
# 그래서 「덤프가 있다」와 「되돌릴 수 있다」가 갈리지 않는다 — 실측으로 10개 중 9개가
# 현재 DB와 어긋나 있던 적이 있다(2026-08-25, 마이그레이션 주석을 고치고 체크섬을 UPDATE한 뒤).
#
# 여기 두는 이유는 `ops/dump-db.sh`에서는 **원리적으로 경보가 안 뜨기 때문**이다. 그 스크립트는
# 방금 뜬 덤프를 목록에 넣은 뒤에 보고하므로 「맞는 것」이 언제나 1 이상이다.
#
# MUST NOT: 이것을 차단으로 승격하지 않는다. 낡음은 `ops/dump-db.sh` 한 번이나 `UPDATE` 한 줄로
# 되돌아가고, ADR-0052가 그 자리의 차단을 이미 기각했다.
echo
echo "== 덤프 =="
dump_dir="$DEPLOY/dumps"
if [[ ! -d "$dump_dir" ]] || ! ls "$dump_dir"/*.dump >/dev/null 2>&1; then
  echo "덤프: 없다 — 재구축 가드가 막는다"
elif ! live="$(psql -d "${DB_NAME:-stock_screener}" -At -F'|' \
       -c "SELECT script, checksum FROM flyway_schema_history ORDER BY 1" 2>/dev/null)" \
     || [[ -z "$live" ]]; then
  # MUST: 판정 불가를 침묵으로 내지 않는다. 사고 직후가 정확히 이 상태다.
  echo "덤프: 현재 DB에 못 붙어 대조 불가 — 파일 수만 $(ls "$dump_dir"/*.dump | wc -l | tr -d ' ')개"
else
  live_sorted="$(printf '%s\n' "$live" | sort)"
  # MUST NOT: TOC의 테이블 수를 현재 테이블 수와 대조해 「손상」으로 세지 않는다.
  # 옛 덤프는 그때의 스키마라 수가 다른 것이 정상이다 — 실측으로 8/13 덤프는 26개(지금은 23개,
  # V31이 배치 메타데이터를 지웠다), 8/20 덤프는 21개(그 뒤 거버넌스 표 둘이 늘었다)다.
  # 그렇게 세면 멀쩡한 덤프가 매번 손상으로 뜨고, **거짓 발견은 침묵보다 나쁘다.**
  same=0; drift=0; missing=0; unknown=0
  for f in "$dump_dir"/*.dump; do
    hist="$(pg_restore -a -t flyway_schema_history -f - "$f" 2>/dev/null \
            | awk -F'\t' '$1 ~ /^[0-9]+$/ {print $5"|"$6}' | sort || true)"
    if [[ -z "$hist" ]]; then unknown=$((unknown+1)); continue; fi
    if [[ "$hist" == "$live_sorted" ]]; then same=$((same+1))
    elif [[ "$(printf '%s\n' "$hist" | cut -d'|' -f1)" == "$(printf '%s\n' "$live_sorted" | cut -d'|' -f1)" ]]; then
      drift=$((drift+1))          # 버전 집합은 같고 체크섬만 갈렸다 → UPDATE로 산다
    else missing=$((missing+1)); fi   # 마이그레이션 자체가 빠졌다 → 부팅이 새로 적용한다
  done
  echo "덤프: 현재 DB와 같음 $same / 체크섬만 갈림 $drift / 마이그레이션 결손 $missing / 판정불가 $unknown"
  # MUST: 안 보는 것을 말한다. 이 대조는 아카이브 앞쪽 4KB짜리 표 하나만 읽으므로
  # **잘린 덤프도 「같음」으로 센다**(실측: 50%로 자른 파일이 33행을 그대로 냈다).
  # 데이터가 얼마나 낡았는지도 안 본다 — 파일 시각이 그것을 답한다.
  echo "덤프: 이 대조는 이력 표만 본다 — 아카이브 온전성과 데이터 신선도는 안 본다"
  [[ "$same" -eq 0 ]] && echo "덤프: **바로 복원되는 것이 하나도 없다** — ops/dump-db.sh를 한 번 뜨거나, 되돌리는 법의 UPDATE를 함께 쓴다"
fi

# 로그가 얼마나 쌓였나. **아무것도 바꾸지 않는다 — 세고 찍기만 한다**(덤프 절과 같은 형태).
#
# 여기 두는 이유는 **회전이 사람의 명령에서만 돌기 때문**이다. `ops/start.sh`가 기동 직전에
# 부르고 `ops/rotate-logs.sh`가 손으로 도는 입구인데, 둘 다 사람이 쳐야 한다(ADR-0043 —
# 앱과 터널은 손으로만 뜨고 죽으면 죽은 채로 있다). 그래서 임계를 넘긴 채 오래 두는 갈래가
# 구조적으로 열려 있고, **그것을 사람이 아는 유일한 경로가 이 절이다.**
#
# MUST NOT: 이것을 차단이나 자동 삭제로 승격하지 않는다. `ops/dump-db.sh`가 덤프에 대해
# "자동으로 지우지 않는다. 지울 것은 사람이 고른다"로 정한 것과 같은 판단이다.
echo
echo "== 로그 =="
if [[ ! -d "$LOG_DIR" ]]; then
  # MUST: 중괄호를 씌운다. 한글 조사가 변수명에 그대로 붙어 bash가 `LOG_DIR이`를 찾고,
  # `set -u`가 그 자리에서 스크립트를 죽인다 — 앱을 한 번도 안 띄운 상태에서만 타는 줄이라
  # 평소에 안 드러난다(2026-09-22 발견).
  echo "로그: ${LOG_DIR}이 없다 — 아직 앱을 띄운 적이 없다"
else
  log_line() {
    local name="$1" max="$2" f="$LOG_DIR/$1" size pct gens
    [[ -f "$f" ]] || { printf '  %-12s 없다\n' "$name"; return 0; }
    size=$(stat -f%z "$f")
    pct=$(awk -v s="$size" -v m="$max" 'BEGIN{printf "%d", s*100/m}')
    gens=$(find "$LOG_DIR" -maxdepth 1 -name "$name.*.gz" | wc -l | tr -d ' ')
    printf '  %-12s %8s  임계의 %3d%%  세대 %d개' "$name" \
      "$(awk -v b="$size" 'BEGIN{if(b>=1048576)printf "%.1fM",b/1048576; else if(b>=1024)printf "%.0fK",b/1024; else printf "%dB",b}')" \
      "$pct" "$gens"
    # 80%는 경고선이다. 넘겨도 아무 일이 안 일어나므로 **말해 주는 것이 전부**다.
    [[ "$pct" -ge 80 ]] && printf '  ← ops/rotate-logs.sh 를 한 번 돌린다'
    printf '\n'
  }
  log_line stdout.log "$ROTATE_STDOUT_MAX"
  log_line stderr.log "$ROTATE_STDERR_MAX"
  log_line tunnel.log "$ROTATE_TUNNEL_MAX"
  echo "  총량 $(du -sh "$LOG_DIR" 2>/dev/null | cut -f1) · 지난 이력: zgrep -A 20 '<문구>' $LOG_DIR/stdout.log.*.gz"
fi
