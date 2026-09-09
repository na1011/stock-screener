#!/bin/bash
#
# 폐기된 시점 재현 저장소를 지운다(ADR-0131).
#
# **에이전트가 못 지워서 있는 스크립트다.** `guard-destructive.sh`가 `rm -r`+`-f`를 하드
# deny하고, 플래그를 바꿔 통과시키는 것은 그 가드의 취지를 무력화하는 것이라 안 한다.
# 사람이 이 스크립트를 실행한다.
#
# **한 번 쓰고 지우는 스크립트다** — 지우고 나면 대상이 없어 아무 일도 안 한다.
# 다음에 같은 것이 필요해지면 그때 다시 만든다.
#
#   ./ops/drop-pit-store.sh          # 무엇을 지울지 보여주고 확인을 묻는다
#   ./ops/drop-pit-store.sh --yes    # 묻지 않는다
set -euo pipefail

TARGET="$HOME/.stock-screener/pit-store"

if [ ! -d "$TARGET" ]; then
  echo "[drop-pit-store] 대상이 없다 — 이미 지웠거나 만든 적이 없다: $TARGET"
  exit 0
fi

# 지우기 전에 **읽는 코드가 정말 없는지** 다시 센다. 이 스크립트를 만든 근거가 그것이고,
# 사이에 누가 되살렸으면 지우면 안 된다.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readers=$(grep -rl "pit-store\|PIT_STORE_DIR" "$ROOT/src" "$ROOT/build.gradle" 2>/dev/null || true)
if [ -n "$readers" ]; then
  echo "[drop-pit-store] 읽는 코드가 남아 있다 — 지우지 않는다:" >&2
  echo "$readers" >&2
  exit 1
fi

echo "[drop-pit-store] 대상: $TARGET"
echo "[drop-pit-store] 크기: $(du -sh "$TARGET" | cut -f1) · 파일 $(find "$TARGET" -type f | wc -l | tr -d ' ')개"
echo "[drop-pit-store] 읽는 코드: 0건(src·build.gradle 전수)"

if [ "${1:-}" != "--yes" ]; then
  read -r -p "[drop-pit-store] 지울까? 되돌릴 수 없다 [y/N] " answer
  case "$answer" in
    y|Y) ;;
    *) echo "[drop-pit-store] 안 지웠다."; exit 0 ;;
  esac
fi

rm -rf "$TARGET"
echo "[drop-pit-store] 지웠다. 남은 것: $(du -sh "$HOME/.stock-screener" 2>/dev/null | cut -f1 || echo 0)"
echo "[drop-pit-store] 이 스크립트도 이제 할 일이 없다 — git rm ops/drop-pit-store.sh"
