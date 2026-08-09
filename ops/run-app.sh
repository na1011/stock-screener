#!/bin/bash
#
# launchd가 실행하는 진입점. **스테이징된 사본이 실행된다** — 저장소 안의 이 파일이 아니라
# ~/Library/Application Support/stock-screener/run-app.sh 다(ops/stage.sh가 복사한다).
# 이유는 그 파일 머리에 있다: 도는 것이 작업 트리를 가리키면 빌드가 곧 배포가 된다.
#
# 그래서 여기서는 저장소를 참조하지 않는다. 자기가 놓인 디렉터리 안의 것만 쓴다.
#
# .env를 읽는 주체가 plist가 아니라 이 스크립트인 이유는, plist가 평문 파일이고
# 백업·동기화·화면 공유를 그대로 따라다니기 때문이다.
#
# MUST NOT: 여기서 값을 echo 하지 않는다. 이 스크립트의 stdout은 로그 파일로 간다.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

if [[ ! -f "$HERE/.env" ]]; then
  echo "[run-app] .env가 없다: $HERE/.env — ops/stage.sh를 먼저 돌려라." >&2
  exit 1
fi
if [[ ! -f "$HERE/app.jar" ]]; then
  echo "[run-app] app.jar가 없다: $HERE — ops/stage.sh를 먼저 돌려라." >&2
  exit 1
fi

# set -a로 감싸면 파일 안의 KEY=VALUE가 그대로 export된다. launchd 환경에는 애초에
# 아무것도 없으므로 "셸 값이 이긴다"는 구분이 여기서는 의미가 없다.
set -a
# shellcheck disable=SC1091
. "$HERE/.env"
set +a

JAVA_BIN="$(/usr/libexec/java_home -v 21 2>/dev/null || true)/bin/java"
if [[ ! -x "$JAVA_BIN" ]]; then
  echo "[run-app] JDK 21을 찾을 수 없다." >&2
  exit 1
fi

echo "[run-app] $(date '+%Y-%m-%d %H:%M:%S') 기동"

# caffeinate로 감싼다. -s는 **전원이 연결됐을 때만** 유휴 절전을 막는다 — 배터리로 도는
# 동안에는 평소대로 잠들므로 방전 걱정이 없다. 뚜껑을 닫으면 이것으로도 잠드는데,
# 그것을 막으려면 sudo pmset -a disablesleep 1이 필요하다(TODO.md).
#
# exec로 넘겨 caffeinate가 이 프로세스를 대체하게 한다. 그래야 launchd가 보는 PID가
# 실제로 앱을 붙들고 있는 프로세스가 되고, KeepAlive의 재시작 판정이 맞는다.
exec /usr/bin/caffeinate -s "$JAVA_BIN" \
  -jar "$HERE/app.jar" \
  --pipeline.scheduler.enabled=true \
  --sentry.environment=production
