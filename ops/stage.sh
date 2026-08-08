#!/bin/bash
#
# 실행에 필요한 것만 TCC 밖으로 복사한다.
#
# IMPORTANT — 저장소가 ~/Desktop 아래에 있고, macOS는 그 경로를 TCC로 보호한다.
# launchd가 띄운 프로세스에는 그 권한이 없어 스크립트 실행조차 거부된다(실측:
# `/bin/bash: .../ops/run-app.sh: Operation not permitted`, exit 126). 터미널에서는
# 멀쩡히 되므로 증상이 "왜 launchd에서만 안 되지"로만 보인다.
#
# 해법으로 /bin/bash에 전체 디스크 접근 권한을 주지 않는다 — TCC 권한은 바이너리 단위라
# 그렇게 하면 bash로 실행되는 모든 것이 보호 경로를 읽게 된다.
#
# 부수 효과가 하나 더 있고 이쪽이 오히려 크다: ~/Desktop은 iCloud 동기화 대상이라
# 산출물이 "이름 2.jar"로 복제되는 일이 실제로 있었다. 도는 jar가 그 밖에 있으면
# 개발 중 재빌드가 도는 인스턴스를 건드리지도 않는다.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY="$HOME/Library/Application Support/stock-screener"

JAR="$(ls -t "$ROOT"/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "[stage] 실행할 jar가 없다. ./gradlew build를 먼저 돌려라." >&2
  exit 1
fi
if [[ ! -f "$ROOT/.env" ]]; then
  echo "[stage] .env가 없다: $ROOT/.env" >&2
  exit 1
fi

mkdir -p "$DEPLOY"
chmod 700 "$DEPLOY"

# .env를 복사하는 것은 이 구조의 대가다. 저장소가 TCC 보호 경로 밖으로 나오면 없앨 수 있다
# (TODO.md가 그것을 권한다). 그때까지는 권한을 좁혀 둔다.
cp "$ROOT/.env" "$DEPLOY/.env"
chmod 600 "$DEPLOY/.env"

cp "$ROOT/ops/run-app.sh" "$DEPLOY/run-app.sh"
chmod 700 "$DEPLOY/run-app.sh"

# 새 이름으로 쓴 뒤 옮긴다. 큰 파일을 제자리에 덮어쓰면 그 순간 재시작이 걸렸을 때
# 반쯤 쓰인 jar를 실행하게 된다.
cp "$JAR" "$DEPLOY/app.jar.tmp"
mv "$DEPLOY/app.jar.tmp" "$DEPLOY/app.jar"

echo "[stage] $(basename "$JAR") → $DEPLOY/app.jar"
