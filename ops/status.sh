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
  echo "자동기동: 없다 (정상 — 부팅·로그인에 뜨는 등록이 하나도 없다)"
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
