#!/bin/bash
#
# 실행에 필요한 것만 저장소 밖으로 복사한다.
#
# IMPORTANT — 이 맥북은 개발 기기이면서 동시에 운영 기기다. 도는 것이 작업 트리를
# 가리키면 빌드가 곧 배포가 된다: `./gradlew clean`이 도는 앱의 jar를 지운다. 도는
# 프로세스는 그대로 멀쩡해 보이므로 증상은 **다음에 켤 때** 나오고, 그때는 빌드가 원인이라는
# 단서가 아무 데도 없다. 그래서 도는 것을 저장소 밖에 둔다(ADR-0034).
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

# 터널 토큰을 파일로 내보낸다. cloudflared를 부팅 데몬에서 걷어 사용자 권한으로 띄우게
# 되면서(ADR-0043) root 소유 토큰을 못 읽기 때문이다. `--token <값>`으로 넘기지 않는
# 이유는 그것이 ps에 그대로 뜨기 때문이고, 그래서 600 파일로 건넨다.
TOKEN="$(grep -m1 '^CLOUDFLARE_TUNNEL_TOKEN=' "$ROOT/.env" | cut -d= -f2- || true)"
if [[ -z "$TOKEN" || "$TOKEN" == "your-tunnel-token" ]]; then
  # 앱은 이것 없이도 돈다. 밖에서 안 보일 뿐이라 실패로 다루지 않고, 대신 조용히 넘기지도 않는다.
  echo "[stage] .env에 CLOUDFLARE_TUNNEL_TOKEN이 없다(또는 자리표시자 그대로다) — 터널은 뜨지 않는다." >&2
else
  # umask를 서브셸에 가둔다. 밖으로 새면 뒤따르는 jar 복사의 권한까지 조용히 바꾼다.
  ( umask 077; printf '%s' "$TOKEN" >"$DEPLOY/tunnel-token" )
fi

# 새 이름으로 쓴 뒤 옮긴다. 큰 파일을 제자리에 덮어쓰면 그 순간 재시작이 걸렸을 때
# 반쯤 쓰인 jar를 실행하게 된다.
cp "$JAR" "$DEPLOY/app.jar.tmp"
mv "$DEPLOY/app.jar.tmp" "$DEPLOY/app.jar"

# 이 jar가 어느 커밋에서 나왔는지 남긴다 — 손 쓰기 절차가 「지금 도는 코드」에 뜬 worktree에서 bootRun을 돌릴 때
# 읽는다(ADR-0173 손 FR 0단계). main 작업 트리는 배포 뒤에도 움직이므로 「HEAD = 배포 커밋」은 금방 거짓이 된다.
# deploy.sh가 빌드 전후 상태(HEAD|빌드 입력의 미커밋 목록 해시)를 넘기면 그것으로 적는다 — 둘이 다르면 빌드 중에 트리가
# 움직인 것이라 커밋으로 가리킬 수 없다. 혼자 돌면 지금 상태로 적고 그 사실(captured=stage)을 함께 남긴다.
# 커밋으로 가리킬 수 없으면 unknown을 남겨 읽는 쪽이 멈추게 한다. 못 적어도 배포는 막지 않되 알린다.
EMPTY_MD5="$(printf '' | md5)"
if [[ -n "${BUILD_STATE_BEFORE:-}" ]]; then
  if [[ "$BUILD_STATE_BEFORE" == "${BUILD_STATE_AFTER:-}" && -n "${BUILD_STATE_BEFORE%%|*}" ]]; then
    commit="${BUILD_STATE_BEFORE%%|*}"
    dirty=$([[ "${BUILD_STATE_BEFORE#*|}" == "$EMPTY_MD5" ]] && echo false || echo true)
  else
    echo "[stage] 빌드 중에 작업 트리가 움직였다 — 이 jar의 커밋을 가리킬 수 없어 unknown을 남긴다." >&2
    commit=unknown; dirty=unknown
  fi
  captured=build
elif commit="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)" \
    && porcelain="$(git -C "$ROOT" status --porcelain -- src frontend gradle build.gradle settings.gradle gradlew 2>/dev/null)"; then
  dirty=$([[ -n "$porcelain" ]] && echo true || echo false)
  captured=stage
else
  echo "[stage] 배포 커밋을 못 읽었다 — unknown을 남긴다(손 쓰기 절차가 멈춘다)." >&2
  commit=unknown; dirty=unknown; captured=stage
fi
{ echo "commit=$commit"; echo "dirty=$dirty"; echo "captured=$captured"; echo "staged_at=$(date '+%F %T')"; } \
  >"$DEPLOY/app.jar.commit"

echo "[stage] $(basename "$JAR") → $DEPLOY/app.jar"
