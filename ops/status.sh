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

# 자동 기동이 되살아나 있으면 여기서 보여야 한다. ADR-0043의 premise와 같은 것을 본다.
found=""
[[ -f "$HOME/Library/LaunchAgents/com.stockscreener.app.plist" ]] && found="$found app-LaunchAgent"
[[ -f "/Library/LaunchDaemons/com.cloudflare.cloudflared.plist" ]] && found="$found cloudflared-LaunchDaemon"
[[ -n "$(crontab -l 2>/dev/null)" ]] && found="$found crontab"
if [[ -n "$found" ]]; then
  echo "자동기동: 되살아났다 —$found  (ADR-0043은 0건을 전제한다)"
else
  echo "자동기동: 없다"
fi
