#!/bin/bash
# PostToolUse: 셸로 파일을 다뤘을 때 그 파일을 지배하는 `.claude/rules/` 규칙을 알린다.
#
# 왜 필요한가 — `paths` 규칙은 매칭 파일을 **Read**할 때 뜬다. `grep`·`sed`·`cat`으로 같은
# 파일을 보거나 고치면 규칙 계층이 통째로 우회되고, **우회됐다는 사실은 어디에도 드러나지 않는다.**
# 2026-08-09 실측: 한 세션에서 `build.gradle`을 4회, `application.yaml`을 2회 셸로 읽었는데
# `config.md`가 한 번도 로드되지 않았다. 그 파일에는 "스케줄러 기본 꺼짐" 같은, 앱을 몇 시간
# 띄워 두는 판단에 직접 걸리는 항목이 있었다.
#
# 규칙 본문이 아니라 포인터를 낸다. 본문을 주입하면 명령마다 수천 자가 되어 아무것도 안 하는
# 것보다 나쁘다(`remind-adr.sh`가 같은 이유로 포인터를 택했다).
#
# 판정 범위를 좁힌 두 가지와 그 비대칭 —
#   1. **읽기 동사 목록을 두지 않는다.** `grep|sed|cat|awk|jq|python -c`를 열거하면 반드시
#      빠뜨리고, 빠뜨린 것은 **침묵**한다. 그래서 "명령이 지배된 파일을 경로로 부르면" 알린다.
#   2. **git 부속 명령은 제외한다.** 이들은 파일을 보는 게 아니라 이름만 부르고, 커밋 메시지
#      heredoc이 산문으로 경로를 언급하는 일이 흔하다(2026-08-09 커밋 셋이 그랬다).
#      여기서는 열거를 써도 된다 — **실패 방향이 반대다.** 빠뜨린 부속 명령은 무해한 알림 한
#      줄이 되고, 읽기 동사의 누락은 침묵이 된다. 이 비대칭이 한쪽만 열거하는 근거다.
#
# 차단하지 않는다(PostToolUse는 구조적으로 차단 불가). `grep`은 저장소 전역 검색처럼 Read가
# 못 하는 일을 하므로 막을 대상이 아니다 — 잃어버린 정보를 되돌려 주기만 한다.
set -euo pipefail

# jq가 없으면 조용히 통과한다. 부재 경보는 check-doc-staleness.sh가 세션 시작에 한 번 낸다 —
# 알림 훅마다 stderr를 내면 그 중앙 경보가 묻힌다.
command -v jq >/dev/null 2>&1 || exit 0

# shellcheck source=lib/bash-text.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/bash-text.sh"

input=$(cat)
cmd=$(echo "$input" | jq -r '.tool_input.command // empty')
session=$(echo "$input" | jq -r '.session_id // "nosession"')
[ -z "$cmd" ] && exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
rules_dir="${root}/.claude/rules"
[ -d "$rules_dir" ] || exit 0

marker_dir="${TMPDIR:-/tmp}/claude-harness-hooks/${session}/remind-rule-on-shell"

# 규칙 파일 단위로 세션당 1회. shasum이 없으면 중복 억제만 포기하고 알림은 계속한다 —
# 알림 훅은 침묵이 아니라 발화 쪽으로 실패한다.
already_fired() {
  local key marker
  key=$(echo "$1" | shasum 2>/dev/null | cut -d' ' -f1 || echo "")
  [ -z "$key" ] && return 1
  marker="${marker_dir}/${key}"
  mkdir -p "$marker_dir" 2>/dev/null || return 1
  [ -f "$marker" ] && return 0
  touch "$marker" 2>/dev/null || true
  return 1
}

# ── heredoc 본문을 먼저 떼어낸다 ────────────────────────────────────────────
# IMPORTANT — 이것이 없으면 커밋 메시지가 오탐의 주범이 된다. 이 저장소는 커밋 메시지를
# heredoc으로 쓰고 그 본문이 `CLAUDE.md`·`docs/progress.md`를 산문으로 언급한다. 아래 세그먼트
# 분리는 줄 단위로도 끊기므로, heredoc 본문의 각 줄이 **첫 단어가 git이 아닌 독립 세그먼트**가
# 되어 git 제외를 그대로 빠져나간다(2026-08-09 테스트에서 실제로 발생). 본문은 명령이 아니라
# 데이터이므로 애초에 훑을 대상이 아니다.
#
# 처음에는 첫 `<<`부터 끝까지 버리고 "놓침을 택한다"고 적었는데, 그 타협은 불필요했다 —
# 공용 함수가 **본문만** 비우고 종료 표시 뒤를 살리므로 `git commit <<MSG … MSG ; grep x
# build.gradle`을 놓치지 않는다(회귀표가 그 줄을 지킨다).
script_heredocs=$(heredoc_bodies_of_consumers "$cmd" 'python|python3|node|ruby|perl|sh|bash|zsh' 2>/dev/null || true)
cmd=$(strip_heredoc_bodies "$cmd")
[ -z "$cmd" ] && [ -z "$script_heredocs" ] && exit 0

# 인터프리터가 소비하는 heredoc **본문은 명령이다.** `python3 <<PY … open(path,'w') … PY`가
# 하네스를 고치는 실제 경로이므로 되살려 함께 훑는다. 커밋 메시지 heredoc은 소비자가 `git`이라
# 여기 걸리지 않는다 — 오탐의 주범이던 그 경로는 그대로 제외된다.
#
# 본문에서는 **따옴표 리터럴만** 토큰으로 뽑는다. 스크립트의 경로는 `open(".claude/x","w")`처럼
# 괄호·쉼표에 붙어 있어 공백 분리로는 토큰이 되지 않는다. 경로는 사실상 언제나 따옴표 안이다.
if [ -n "$script_heredocs" ]; then
  script_tokens=$(printf '%s' "$script_heredocs" \
    | grep -oE "'[^']*'|\"[^\"]*\"" 2>/dev/null | tr -d "\"'" | tr '\n' ' ' || true)
  [ -n "$script_tokens" ] && cmd="${cmd}"$'\n'"${script_tokens}"
fi

# ── 명령을 세그먼트로 쪼갠다 ────────────────────────────────────────────────
# `git log -- build.gradle && grep x application.yaml` 처럼 잡음 세그먼트와 실제 읽기가
# 한 명령에 섞이므로, 명령 전체를 버리거나 전체를 받는 이분법을 쓰지 않는다.
# `||`를 `|`보다 먼저 바꿔야 파이프 치환이 그것을 먹지 않는다.
segments=$(printf '%s' "$cmd" | sed 's/&&/\n/g; s/||/\n/g; s/;/\n/g; s/|/\n/g')

# ── 규칙별 glob 목록 (`rule<TAB>glob`) ──────────────────────────────────────
# `**`를 `*`로 접는다. bash `[[ ]]` 패턴에서 `*`는 `/`를 넘으므로 `src/main/java/**`가
# 의도대로 동작한다. **이 접기는 bash 전용이다** — zsh에서 같은 대조를 하면 0건이 나온다.
rule_globs=$(
  for rule in "$rules_dir"/*.md; do
    [ -f "$rule" ] || continue
    base=$(basename "$rule")
    awk -v r="$base" '
      /^paths:[[:space:]]*$/ { f = 1; next }
      f && /^[[:space:]]*-[[:space:]]*/ {
        sub(/^[[:space:]]*-[[:space:]]*/, "")
        gsub(/^["'"'"']|["'"'"']$/, "")
        gsub(/\*\*/, "*")
        if ($0 != "" && $0 != "-" && $0 != "--") print r "\t" $0
        next
      }
      f { exit }
    ' "$rule" 2>/dev/null || true
  done
)
[ -z "$rule_globs" ] && exit 0

matched=""
while IFS= read -r seg; do
  [ -z "$seg" ] && continue

  # 첫 단어가 git이고 부속 명령이 "이름만 부르는" 것이면 이 세그먼트를 버린다.
  # shellcheck disable=SC2086  # 단어 분리가 의도다
  set -- $seg
  [ $# -eq 0 ] && continue
  # MUST NOT: 파일을 **쓰는** 부속 명령을 여기 넣지 않는다. `checkout`·`restore`·`apply`·
  # `rm`·`mv`는 이름만 부르는 것이 아니라 작업 트리를 고친다 — 되돌리기와 패치 적용은
  # 하네스를 고치는 가장 흔한 형태이고, 제외하면 그 경로가 통째로 침묵한다.
  if [ "$(basename "${1}")" = "git" ] && [ $# -ge 2 ]; then
    case "$2" in
      add|commit|diff|log|show|status|blame)
        continue ;;
    esac
  fi

  for tok in $seg; do
    # 따옴표만 걷어낸다. 나머지 정규화는 하지 않는다 — 변수가 든 토큰(`"$F"`)은 원리적으로
    # 해석할 수 없고, 그것을 추측하면 오탐이 된다.
    tok="${tok%\"}"; tok="${tok#\"}"
    tok="${tok%\'}"; tok="${tok#\'}"
    tok="${tok#"${root}/"}"
    # `./` 접두어를 벗긴다. glob은 `./` 없이 쓰여 있어 이 한 글자로 매칭이 어긋난다.
    tok="${tok#./}"
    # 전역 사본을 저장소 사본과 같은 glob으로 판정한다. `harness.md`가 차단 가드를 고치면
    # `~/.claude/hooks/`의 사본도 함께 고치라고 요구하므로 같은 규칙이 걸려야 한다.
    case "$tok" in
      "$HOME"/.claude/*) tok=".claude/${tok#"$HOME"/.claude/}" ;;
      '~/.claude/'*)     tok=".claude/${tok#'~/.claude/'}" ;;
    esac
    case "$tok" in -*|"") continue ;; esac

    while IFS=$'\t' read -r rname rglob; do
      [ -z "$rglob" ] && continue
      # 두 번째 대조는 **디렉터리 토큰**을 위한 것이다. `find .claude/hooks -exec sed …`는
      # 그 트리의 파일을 고치는데 토큰은 디렉터리 하나뿐이라 파일 glob과 매칭되지 않는다.
      # shellcheck disable=SC2053  # 우변을 따옴표로 감싸면 glob이 아니라 리터럴이 된다
      if [[ "$tok" == $rglob ]] || [[ "$rglob" == "$tok"/* ]]; then
        case "$matched" in
          *"|${rname}|"*) ;;
          *) matched="${matched}|${rname}|" ;;
        esac
        break
      fi
    done <<< "$rule_globs"
  done
done <<< "$segments"

[ -z "$matched" ] && exit 0

msg=""
while IFS= read -r rname; do
  [ -z "$rname" ] && continue
  already_fired "${rname}|shell-touch" && continue
  [ -n "$msg" ] && msg="${msg}"$'\n'
  msg="${msg}셸로 다룬 파일을 \`.claude/rules/${rname}\`가 지배한다. \`paths\` 규칙은 파일을 Read할 때 뜨므로 이 명령으로는 로드되지 않았다 — 그 파일의 함정이 이번 작업에 걸리면 Read로 열어라."
done <<< "$(printf '%s' "$matched" | tr '|' '\n' | grep -v '^$' | sort -u || true)"

[ -z "$msg" ] && exit 0

jq -n --arg ctx "$msg" \
  '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $ctx
    }
  }'
exit 0
