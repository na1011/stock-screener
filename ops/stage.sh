#!/bin/bash
#
# 실행에 필요한 것만 저장소 밖으로 복사한다.
#
# IMPORTANT — 이 맥북은 개발 기기이면서 동시에 운영 기기다. 도는 것이 작업 트리를
# 가리키면 빌드가 곧 배포가 된다: `./gradlew clean`이 도는 앱의 jar를 지우고, 30초 뒤
# KeepAlive가 되살리려 할 때 띄울 것이 없다. 빌드의 부수 효과로 사이트가 내려가는데
# 밖에서 502로만 보인다. 그래서 도는 것을 저장소 밖에 둔다(ADR-0034).
#
# MUST NOT: 아래 .env 복사를 심볼릭 링크로 바꾸지 않는다. 링크는 저장소 경로를 이 트리에
# 다시 박아, "run-app.sh는 저장소를 참조하지 않는다"는 성질을 깬다. 그러면 저장소를
# 옮기거나 지웠을 때 그 순간이 아니라 몇 시간 뒤 아무 재시작 시점에 앱이 뜨지 않는다.
#
# 처음 이 구조를 만든 이유는 달랐다 — 저장소가 ~/Desktop 아래에 있었고 macOS가 그
# 경로를 TCC로 보호해, launchd가 띄운 프로세스는 스크립트 실행조차 거부됐다(실측:
# `/bin/bash: .../ops/run-app.sh: Operation not permitted`, exit 126). 저장소를 옮겨
# 그 이유는 사라졌지만 구조는 남았다. 경위는 ADR-0033과 ADR-0034에 있다.

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

# .env를 복사하는 것은 이 구조의 대가다. 저장소가 TCC 밖으로 나온 뒤 다시 재고 그대로
# 두기로 했다(ADR-0034) — 두 파일은 같은 사용자의 600이라 노출 면적이 같고, 배포해야
# 반영된다는 규칙이 jar와 시크릿에 똑같이 적용된다.
cp "$ROOT/.env" "$DEPLOY/.env"
chmod 600 "$DEPLOY/.env"

cp "$ROOT/ops/run-app.sh" "$DEPLOY/run-app.sh"
chmod 700 "$DEPLOY/run-app.sh"

# 새 이름으로 쓴 뒤 옮긴다. 큰 파일을 제자리에 덮어쓰면 그 순간 재시작이 걸렸을 때
# 반쯤 쓰인 jar를 실행하게 된다.
cp "$JAR" "$DEPLOY/app.jar.tmp"
mv "$DEPLOY/app.jar.tmp" "$DEPLOY/app.jar"

echo "[stage] $(basename "$JAR") → $DEPLOY/app.jar"
