#!/bin/bash
# SessionStart(전역): 전역 하네스가 조용히 낡는 자리를 보고한다. 차단하지 않는다.
#
# 왜 필요한가 — 프로젝트 쪽에는 `check-doc-staleness.sh`가 있는데 전역에는 아무것도 없었다.
# 그래서 `settings.json`의 `autoMode.environment`가 "배포 파이프라인도 운영 환경도 없다"라고
# 주장하는 동안 실제로는 launchd 상시 구동 + 외부 접근 가능한 터널 + 실데이터 DB가 생겼고,
# auto 모드가 그 거짓 위에서 판정을 계속했다. 몇 주간 아무 신호가 없었다.
#
# 평문 stdout을 쓴다. `SessionStart`의 평문 stdout은 그대로 컨텍스트가 되고 jq가 필요 없다 —
# 의존성 부재를 그 의존성으로 알릴 수 없다는 규칙(`.claude/rules/harness.md`)과 같은 이유다.
#
# 활성 등록은 `~/.claude/hooks/`의 사본 하나뿐이고, `.claude/hooks/`의 사본은 버전관리·드리프트
# 대조용이며 등록하지 않는다. `~/.claude`는 git도 백업도 없어, 전역에만 두면 이 파일이 날아갔을
# 때 되살릴 곳이 없다 — 그리고 이것이 사라지면 **다른 전역 훅이 죽은 것도 아무도 모른다**(§1의
# 역방향 검사가 여기 있다). 자기 자신의 소실은 원리적으로 자기가 감지할 수 없으므로, 의심되면
# 저장소 사본을 손으로 돌린다: `bash .claude/hooks/check-global-staleness.sh`
#
# MUST NOT: 검사 대상 훅을 이름으로 열거하지 않는다. 열거는 곧 낡는 목록이 되고, 낡은 목록은
# "덮여 있다"고 믿게 만든다 — 이 검사가 잡으려는 병과 같은 병이다. 파일 목록은 디렉터리에서
# 유도한다.
set -uo pipefail

findings=""
add() { findings="${findings}- $1"$'\n'; }

global_hooks="$HOME/.claude/hooks"

# ── 1. 전역 사본 가드가 참조 구현과 갈렸는가 ────────────────────────────────
#
# 안전 가드는 두 곳에 있다. 저장소 안(클론에서도 살아남는다)과 전역(훅이 없는 프로젝트와
# 저장소 밖 세션을 덮는다). 둘 다 필요하지만 사본이라 갈릴 수 있고, 갈린 사실은 어디에도
# 드러나지 않는다. 참조 구현이 정본이다.
reference="${CLAUDE_HARNESS_REFERENCE:-$HOME/dev/stock-screener}/.claude/hooks"

if [ ! -d "$reference" ]; then
  add "참조 구현($reference)을 찾지 못해 전역 가드의 드리프트를 판정하지 못했다. 저장소를 옮겼다면 CLAUDE_HARNESS_REFERENCE 환경변수로 새 경로를 알려라. 그때까지 전역 사본이 낡아도 알 수 없다."
elif ! command -v shasum >/dev/null 2>&1; then
  add "shasum이 없어 전역 가드의 드리프트를 판정하지 못했다."
else
  drifted=""
  # 전역에 있고 참조에도 같은 이름이 있는 것만 본다. 전역 전용 훅(데스크톱 알림 등)은
  # 대조 대상이 아니고, 참조에만 있는 훅은 프로젝트 전용이라 전역에 없는 것이 정상이다.
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    rel="${f#"$global_hooks"/}"
    [ -f "$reference/$rel" ] || continue
    a=$(shasum -a 256 "$f" 2>/dev/null | cut -d' ' -f1)
    b=$(shasum -a 256 "$reference/$rel" 2>/dev/null | cut -d' ' -f1)
    [ -n "$a" ] && [ -n "$b" ] && [ "$a" != "$b" ] && drifted="${drifted}${rel} "
  done <<< "$(find "$global_hooks" -name '*.sh' -type f 2>/dev/null)"

  if [ -n "$drifted" ]; then
    add "전역 가드 사본이 참조 구현과 갈렸다: ${drifted}— 한쪽만 고친 것이다. 어느 쪽이 새 것인지 확인하고 맞춰라. 갈린 채로 두면 저장소 안에서는 막히는 것이 저장소 밖에서는 통과한다."
  fi

  # 반대 방향. 위 순회는 **전역 디렉터리**를 돈다 — 전역 사본이 지워지면 순회에 안 들어와
  # 침묵하고, 그러면 기제가 통째로 죽어도 모든 검사가 그린이다. 참조 쪽에서 "전역에 등록된다"고
  # 스스로 밝힌 파일을 찾아 전역에 있는지 본다.
  #
  # MUST NOT: 이름을 열거하지 않는다(위 MUST NOT과 같은 이유). 표식으로 유도한다 —
  # 그 표식은 훅 자신의 헤더에 있고, 사본을 만든 사람이 지울 이유가 없는 문장이다.
  missing=""
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    rel="${f#"$reference"/}"
    [ -f "$global_hooks/$rel" ] || missing="${missing}${rel} "
  done <<< "$(grep -rl '활성 등록은 `~/.claude/hooks/`' "$reference" --include='*.sh' 2>/dev/null)"

  if [ -n "$missing" ]; then
    add "전역에 등록된다고 스스로 밝힌 훅이 전역에 없다: ${missing}— 그 훅은 지금 어느 프로젝트에서도 돌지 않는다. 저장소 사본을 \$HOME/.claude/hooks/로 복사하고 ~/.claude/settings.json의 등록도 확인해라(등록 형태는 그 파일 헤더에 있다). ~/.claude는 버전관리도 백업도 없어 이 검사 말고는 아무도 알려주지 않는다."
  fi
fi

# ── 1b. jq가 있는가 ────────────────────────────────────────────────────────
#
# 전역 훅 대부분이 jq에 의존하는데 **전역에는 그 부재를 알릴 보고자가 이 훅뿐**이다.
# 프로젝트 쪽 `check-doc-staleness.sh`가 같은 일을 하지만 그것은 이 저장소에서만 돈다.
# MUST: 이 검사는 jq를 쓰지 않는다 — 의존성 부재를 그 의존성으로 알릴 수 없다.
if ! command -v jq >/dev/null 2>&1; then
  add "jq가 없다. jq에 의존하는 전역 훅이 전부 판정을 못 하는 상태다 — 어느 것인지는 ~/.claude/settings.json의 등록 목록이 답한다. 시크릿 차단과 파괴적 명령 차단이 거기 섞여 있으면 복구 전에는 그 명령들을 직접 검토해야 한다."
fi

# ── 2. 낡은 규칙을 담은 백업 파일이 남아 있는가 ──────────────────────────────
#
# `~/.claude`는 grep 대상이다. 규칙의 구판이 함께 잡히면 어느 쪽이 현행인지 갈리지 않는다.
leftovers=$(find "$HOME/.claude" -maxdepth 1 \( -name '*.bak' -o -name '*.bak-*' \) 2>/dev/null | tr '\n' ' ')
if [ -n "${leftovers// /}" ]; then
  add "전역 설정 디렉터리에 백업 파일이 남아 있다: ${leftovers}— 안에 현행과 다른 구판 규칙이 들어 있으면 ~/.claude를 grep한 세션이 낡은 규칙을 현행으로 읽는다. ~/.claude 밖으로 옮기거나 지워라(삭제는 사용자 확인 사항이다)."
fi

[ -z "$findings" ] && exit 0

echo "전역 하네스 점검에서 걸린 것이 있다."
echo
printf '%s' "$findings"
exit 0
