#!/bin/bash
#
# 새 jar를 빌드하고 도는 인스턴스를 갈아 끼운다.
#
# 스크립트로 두는 이유는 순서가 틀리기 쉽기 때문이다 — 재기동을 먼저 하면 옛 jar가
# 다시 뜨고, 빌드만 하면 도는 것은 계속 옛것이다. 그 상태는 "고쳤는데 왜 안 바뀌지"로만
# 보이고, 화면에는 아무 단서가 없다.
#
# **터널은 건드리지 않는다.** 바뀐 것은 jar뿐이고, 터널을 함께 내렸다 올리면 재배포마다
# 밖에서 보이는 시간이 길어진다. 터널이 안 떠 있으면 아래 외부 확인이 그렇게 말해 준다.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=ops/lib.sh
. "$HERE/lib.sh"
cd "$ROOT"

echo "== 빌드 =="
./gradlew clean build

echo "== 스테이징 =="
"$HERE/stage.sh"

echo "== 재기동 =="
# 돌고 있지 않아도 실패로 다루지 않는다. 배포는 "지금 도는 것을 새것으로 바꾼다"이고,
# 꺼져 있었으면 새것으로 켜는 것이 같은 의도다.
if running_pid "$APP_PID_FILE" "$APP_PATTERN" >/dev/null; then
  "$HERE/stop.sh" --app
fi
"$HERE/start.sh" --app

echo "== 확인 =="
# 터널까지 살아 있는지는 밖에서 물어야 안다. 로컬만 보면 cloudflared가 죽어도 초록이다.
echo -n "외부: "
if curl -sf --max-time 10 "$HEALTH_PUBLIC"; then
  echo
else
  echo "응답 없음 — 터널이 안 떠 있다. ops/start.sh --tunnel" >&2
  exit 1
fi
