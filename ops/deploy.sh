#!/bin/bash
#
# 새 jar를 빌드하고 도는 인스턴스를 갈아 끼운다.
#
# 스크립트로 두는 이유는 순서가 틀리기 쉽기 때문이다 — kickstart를 먼저 하면 옛 jar가
# 다시 뜨고, 빌드만 하면 도는 것은 계속 옛것이다. 그 상태는 "고쳤는데 왜 안 바뀌지"로만
# 보이고, 화면에는 아무 단서가 없다.

set -euo pipefail

LABEL="com.stockscreener.app"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOMAIN="gui/$(id -u)"
cd "$ROOT"

echo "== 빌드 =="
./gradlew clean build

echo "== 스테이징 =="
"$ROOT/ops/stage.sh"

echo "== 재기동 =="
if ! launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
  echo "등록되어 있지 않다. ops/install-agent.sh를 먼저 실행하라." >&2
  exit 1
fi
launchctl kickstart -k "$DOMAIN/$LABEL"

echo "== 확인 =="
for _ in $(seq 1 60); do
  if curl -sf --max-time 2 http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo "OK — $(curl -s http://localhost:8080/actuator/health)"
    # 터널까지 살아 있는지는 밖에서 물어야 안다. 로컬만 보면 cloudflared가 죽어도 초록이다.
    echo -n "외부: "
    curl -s --max-time 10 https://screener.na1011.net/actuator/health || echo "(응답 없음 — cloudflared를 확인하라)"
    echo
    exit 0
  fi
  sleep 1
done
echo "기동하지 못했다. ~/Library/Logs/stock-screener/stderr.log를 본다." >&2
exit 1
