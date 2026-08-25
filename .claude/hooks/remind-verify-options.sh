#!/bin/bash
# PostToolUse(AskUserQuestion): 선택지를 올렸으면 그 선택지 자체도 검증 대상임을 알린다.
#
# 활성 등록은 `~/.claude/hooks/`의 사본 하나뿐이고, `.claude/hooks/`의 사본은 버전관리·드리프트
# 대조용이며 등록하지 않는다. `~/.claude`는 git도 Time Machine도 없어 전역 사본만 두면 파일이
# 날아갔을 때 복구할 곳이 없다. 등록 형태도 여기 적는다 — 파일과 등록이 같은 자리에 있으면 갈릴
# 수 없다. `~/.claude/settings.json`의 `hooks`에:
#
#   "PostToolUse": [ { "matcher": "AskUserQuestion",
#     "hooks": [ { "type": "command", "command": "$HOME/.claude/hooks/remind-verify-options.sh" } ] } ]
#
# `${CLAUDE_PROJECT_DIR}`를 쓰지 않는다 — 전역에서 그것은 "지금 열린 프로젝트"로 풀린다.
#
# 왜 필요한가 — 사용자가 고른 뒤 제출까지 가는 동안 **선택지를 다시 검증하지 않는 것**이
# 실측 42%다(계획 세션 기준, 선택지를 올린 세션 33개 중 14개). 100%가 아니라 가르는 신호다.
# 이 훅은 **틀린 선택지 자체를 막지 못한다** — 사용자가 이미 고른 뒤에 뜬다. 덮는 것은 그 다음
# 단계다. 선택지 품질은 지시문이 덮는다.
#
# 언제 우는가 — **직전 반증 이후 첫 질문**에만. 세션당 1회는 절반을 놓치고(선택지를 올린 세션의
# 절반이 2회 이상 묻는다), 질문 해시는 실측 188건 중 0건만 억제해 사실상 매번 발화다. 그래서
# 트랜스크립트의 서브에이전트 호출 수를 키로 쓴다.
#
# MUST: 키에서 `Explore`를 뺀다. 안 빼면 세는 것이 "반증을 받았다"가 아니라 "코드 검색을 했다"가
# 된다 — Explore 비중이 프로젝트별 57%·78%·70%다.
#
# MUST NOT: `grep -c`를 맨몸으로 쓰지 않는다. 0건일 때 종료 코드가 1이라 `set -euo pipefail`
# 아래에서 훅이 그 자리에서 죽고, PostToolUse는 차단이 안 되므로 그 죽음이 **정상 억제와
# 구분되지 않는 침묵**이 된다. 잃는 것이 하필 호출 수 0인 시점 = 세션 첫 질문이다.
#
# MUST: `agent_id`는 `// "main"`으로 받는다. 메인에서는 그 키가 **부재**라 `!= "main"` 비교를
# 그대로 쓰면 메인 전체가 침묵해 훅이 통째로 죽는다.
#
# 반증을 한 번도 안 한 세션에서는 첫 질문에만 울고 그 뒤 침묵한다. 의도다 — 그쪽은 "반증 없이
# 제출"의 영역이고 그것은 `remind-plan-obligations.sh`가 이미 6%까지 내려놓았다.
#
# 손으로 태우는 법(세 경로) — `T`는 실제 트랜스크립트 하나, 세션 id는 반드시 가짜로 준다:
#   T=$(ls -S ~/.claude/projects/*/*.jsonl | head -1)
#   J() { jq -n --arg t "$T" --arg s "probe-$$" "${1:-{}}"' * {transcript_path:$t,session_id:$s,tool_name:"AskUserQuestion"}'; }
#   J | bash remind-verify-options.sh        # ① 발화
#   J | bash remind-verify-options.sh        # ② 같은 입력 → 침묵
#   J '{agent_id:"agent-x"}' | bash remind-verify-options.sh   # ③ 침묵
#
# jq가 없으면 조용히 통과한다. 전역에는 부재를 알릴 중앙 보고자가 없어
# `check-global-staleness.sh`가 그 한 줄을 낸다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
tool=$(echo "$input" | jq -r '.tool_name // empty')
[ "$tool" = "AskUserQuestion" ] || exit 0

session=$(echo "$input" | jq -r '.session_id // "nosession"')
agent=$(echo "$input" | jq -r '.agent_id // "main"')
[ "$agent" = "main" ] || exit 0

emit() {
  jq -n --arg ctx "선택지를 올렸다. **그 선택지와 권장 근거 자체도 검증 대상이다.**
- 설명에 **재보지 않은 예측을 사실로 적지 않았는가.** 재보고 적거나, 못 쟀으면 「재보지 않았다」를 함께 적는다 — 틀린 설명 위에서 받은 승인은 승인이 아니다.
- **더 싼 안이 빠지지 않았는가**, 권장안이 **이미 기각된 것은 아닌가.**
- 계획 단계면 그 선택지를 반증 에이전트에 함께 넣는다. 승인 뒤 근거가 거짓으로 드러나면 다시 묻는다.
- 설명에 「범위 밖 / 별개의 결함 / 원래 있던 것」을 적으면 **갈래를 함께 적는다** — 현재 작업과 큰 틀에서 같이 묶여 있으면 이번에 처리하고, 아니면 넘긴다. 무시는 갈래가 아니다." \
    '{hookSpecificOutput: {hookEventName: "PostToolUse", additionalContext: $ctx}}'
  exit 0
}

transcript=$(echo "$input" | jq -r '.transcript_path // empty')
# 읽을 수 없으면 운다 — 알림 훅은 침묵이 아니라 발화 쪽으로 실패한다.
[ -n "$transcript" ] && [ -r "$transcript" ] || emit

# 반증 호출 수. Explore는 읽기 전용 탐색이라 뺀다. 0건이어도 죽지 않게 받는다.
launches=$(grep -o '"subagent_type":"[^"]*"' "$transcript" 2>/dev/null | grep -vc '"Explore"' || true)
launches=${launches:-0}

marker_dir="${TMPDIR:-/tmp}/claude-harness-hooks/${session}-${agent}/remind-verify-options"
# 마커를 못 만들면 중복 억제를 포기하고 알린다. mkdir은 원자적이라 같은 이벤트에 두 번 걸려도
# 한 번만 통과한다 — `[ -f ]` 뒤 `touch`는 그 보장이 없다.
if mkdir -p "$marker_dir" 2>/dev/null; then
  mkdir "${marker_dir}/${launches}" 2>/dev/null || exit 0
fi

emit
