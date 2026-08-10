#!/bin/bash
#
# ops/start.sh가 실행하는 진입점. **스테이징된 사본이 실행된다** — 저장소 안의 이 파일이 아니라
# ~/Library/Application Support/stock-screener/run-app.sh 다(ops/stage.sh가 복사한다).
# 이유는 그 파일 머리에 있다: 도는 것이 작업 트리를 가리키면 빌드가 곧 배포가 된다.
#
# 그래서 여기서는 저장소를 참조하지 않는다. 자기가 놓인 디렉터리 안의 것만 쓴다.
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

# set -a로 감싸면 파일 안의 KEY=VALUE가 그대로 export된다. 여기서는 파일이 이긴다 —
# 운영 인스턴스가 무엇을 들고 도는지는 셸이 아니라 이 .env 하나가 답해야 한다.
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

# MUST: exec으로 넘긴다. 이 프로세스가 그대로 JVM이 되어야 start.sh가 기록한 PID로
# stop.sh의 kill이 앱에 닿는다. 중간에 무언가를 끼우면 PID 파일이 그 중간 프로세스를
# 가리키고, 그것을 죽여도 앱은 남는다 — 절전 방지(caffeinate)를 여기가 아니라
# start.sh에서 -w로 따로 붙이는 이유가 그것이다(ADR-0043).
exec "$JAVA_BIN" \
  -jar "$HERE/app.jar" \
  --pipeline.scheduler.enabled=true \
  --sentry.environment=production
