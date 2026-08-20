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

echo "== 빌드 =="
./gradlew clean build

echo "== 스테이징 =="
"$HERE/stage.sh"

echo "== 앱 재기동 =="
# 돌고 있지 않아도 실패로 다루지 않는다. 배포는 "지금 도는 것을 새것으로 바꾼다"이고,
# 꺼져 있었으면 새것으로 켜는 것이 같은 의도다.
if running_pid "$APP_PID_FILE" "$APP_PATTERN" >/dev/null; then
  "$HERE/stop.sh" --app
fi
"$HERE/start.sh" --app

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
