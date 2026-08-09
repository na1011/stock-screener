#!/bin/bash
# SessionEnd: 적재 프로세스를 켜 둔 채 세션이 끝나면 **사람에게** 알린다.
#
# 이 훅의 청중은 에이전트가 아니라 사람이다. 세션이 끝나는 시점이라 에이전트는 이 출력을
# 읽고 행동할 수 없다 — 그래서 데스크톱 알림을 함께 띄운다. 그것이 켜 둔 프로세스가
# **그 자리에서** 사람에게 닿는 유일한 경로다.
#
# 왜 다른 계층으로 안 되는가 —
#   SessionStart 통지는 다음 세션, 즉 **이미 커넥션이 남고 전제 검사가 무너진 뒤**에 뜬다.
#   PostToolUse 통지는 gradle을 다시 돌려야 걸린다. 아무것도 안 하고 세션을 끝내면 침묵이다.
#   그 침묵이 "적재를 켜 둔 채 잊는다"가 반복되는 자리다.
#
# MUST NOT: 여기서 프로세스를 죽이지 않는다. 몇 시간짜리 적재가 정상적으로 도는 중일 수도
# 있고, 그것을 세션 종료로 판정해 끄면 되돌릴 수 없는 손실이 된다(`harness-core.md` —
# 차단은 되돌릴 수 없는 것에만). 판단은 사람에게 남긴다.
set -uo pipefail

hooks_dir="$(dirname "${BASH_SOURCE[0]}")"
[ -r "${hooks_dir}/lib/load-process.sh" ] || exit 0
# shellcheck source=lib/load-process.sh
. "${hooks_dir}/lib/load-process.sh"

pids=$(stray_load_pids) || exit 0
[ -z "$pids" ] && exit 0

msg="적재 프로세스가 아직 돌고 있다 (PID ${pids}). 끝난 잡이면 지금 끈다."

# 트랜스크립트에도 남긴다. 사람이 나중에 "왜 남아 있었지"를 되짚는 자리가 여기다.
echo "$msg 잡이 완주하면 BootstrapRunner가 스스로 내려간다 — 남아 있다는 것은 중간에 끊겼거나 --job 없이 띄웠다는 뜻이다. PID를 특정해서 끈다(pkill -f java 는 운영 앱을 함께 죽인다)."

# 데스크톱 알림. 전역 notify-desktop.sh와 같은 수단이지만 그쪽은 Notification 이벤트
# 전용이고 입력 계약이 달라(`.message`를 jq로 읽는다) 여기서 재사용하지 않는다.
# jq에 의존하지 않는 것도 의도다 — 이 경로는 jq 없이도 떠야 한다.
command -v osascript >/dev/null 2>&1 || exit 0
osascript - "Claude Code · $(basename "${CLAUDE_PROJECT_DIR:-$PWD}")" "$msg" >/dev/null 2>&1 <<'APPLESCRIPT' || true
on run argv
  display notification (item 2 of argv) with title (item 1 of argv) sound name "Glass"
end run
APPLESCRIPT
exit 0
