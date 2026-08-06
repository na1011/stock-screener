#!/bin/bash
# SessionStart guard: 문서의 시점 의존적 주장과 실제 코드 상태를 대조해 불일치만 보고한다.
# 차단하지 않는다. 불일치가 없으면 아무것도 출력하지 않고 조용히 통과한다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || exit 0

findings=""
add() { findings="${findings}- $1"$'\n'; }

# 1. progress.md가 주입 상한을 넘겨 세션 시작 주입이 잘리는가
#
# 이 검사가 없던 동안 실제로 잘렸다. inject-progress.sh는 잘렸다는 사실을 주입문 끝에
# 덧붙이지만, 그 문구 자체가 잘린 문서 뒤에 있어 사람 눈에 잘 띄지 않는다. 결과는
# "뒷부분이 다음 세션에 전달되지 않는데 아무도 모르는" 상태다 — 이 저장소가 가장 경계하는
# 조용한 실패다. 세션 시작에 한 줄로 보고해 그것을 깬다.
#
# 문자 수로 센다. inject-progress.sh가 ${#content}로 자르는데 그것이 바이트가 아니라
# 문자임을 실측 확인했다(8,000자 지점에서 정확히 끊겼다). 바이트로 세면 한글 문서에서
# 상한이 3배 엄격해져 매 세션 오탐이 되고, 오탐이 반복되면 이 검사도 무시된다.
inject_hook=".claude/hooks/inject-progress.sh"
if [ -f docs/progress.md ] && [ -f "$inject_hook" ]; then
  # 상한을 이 파일에 복제하지 않고 주입 훅에서 읽는다. 두 곳에 적으면 한쪽만 바뀌고,
  # 그때 이 검사는 통과하는데 주입은 잘리는 최악의 조합이 된다.
  inject_limit=$(grep -oE '^limit=[0-9]+' "$inject_hook" 2>/dev/null | head -1 | cut -d= -f2)
  if [ -n "${inject_limit:-}" ]; then
    len=$(wc -m < docs/progress.md 2>/dev/null | tr -d ' ')
    if [ -n "$len" ] && [ "$len" -gt "$inject_limit" ]; then
      add "docs/progress.md가 ${len}자로 주입 상한(${inject_limit}자)을 넘는다. 세션 시작 주입이 잘려 뒷부분이 다음 세션에 전달되지 않는다. 정본이 다른 곳(adr/·api-spec/·design/)에 있는 내용을 포인터로 바꿔 줄여라."
    fi
  fi
fi

# 2. 프론트엔드 부재 주장 vs 실제
if [ -d frontend ] || [ -d src/main/frontend ] || [ -d src/main/resources/static/src ] \
   || [ -n "$(find . -name '*.vue' -not -path './.git/*' -not -path './build/*' -print -quit 2>/dev/null)" ]; then
  if grep -qsE '프론트엔드 (디렉터리|디렉토리)?( 없|.*아직)' docs/progress.md README.md; then
    add "진행 상태/README가 프론트엔드 부재를 주장하지만 실제 프론트엔드 파일·디렉터리가 있다."
  fi
fi

# 3. ADR 문서가 필수 필드(applies_to / 검증)를 갖췄는가
# 실제 ADR은 ADR-NNNN- 접두어를 쓰므로 0000-template.md는 자연히 제외된다.
if [ -d docs/adr ]; then
  incomplete=""
  while IFS= read -r adr; do
    [ -z "$adr" ] && continue
    missing=""
    grep -q '^applies_to:' "$adr" || missing="applies_to "
    grep -q '^## 검증'     "$adr" || missing="${missing}검증"
    [ -n "$missing" ] && incomplete="${incomplete}$(basename "$adr")(${missing}) "
  done <<< "$(find docs/adr -maxdepth 1 -name 'ADR-[0-9][0-9][0-9][0-9]-*.md' 2>/dev/null)"
  if [ -n "$incomplete" ]; then
    add "필수 항목이 빠진 ADR이 있다: ${incomplete}— applies_to 없이는 관련 작업에서 로드되지 않고, 검증 절 없이는 준수 여부를 확인할 수 없다."
  fi
fi

# 4. data-pipeline.md의 paths glob이 실제 패키지와 매칭되는가
# 부트스트랩 클래스만 있는 골격 단계에서는 검사하지 않는다. 매 세션 같은 경고가 뜨면 신호가 무뎌진다.
# 패키지 깊이로 게이트하지 않는 이유: 패키지 구조는 프로젝트마다 달라 깊이 가정이 쉽게 어긋난다.
rules="./.claude/rules/data-pipeline.md"
if [ -f "$rules" ] && [ -d src/main/java ]; then
  domain_src=$(find src/main/java -name '*.java' \
                 -not -name '*Application.java' \
                 -not -name '*ApplicationTests.java' \
                 -print -quit 2>/dev/null)
  if [ -n "$domain_src" ]; then
    matched=false
    while IFS= read -r seg; do
      [ -z "$seg" ] && continue
      if find src/main/java -type d -name "$seg" -print -quit 2>/dev/null | grep -q .; then
        matched=true; break
      fi
    done <<< "$(grep -oE '"\*\*/[a-z-]+/\*\*"' "$rules" | sed 's|"\*\*/||; s|/\*\*"||')"
    if [ "$matched" = false ]; then
      add "data-pipeline.md의 paths glob이 실제 패키지 구조와 하나도 매칭되지 않는다. 규칙이 로드되지 않는다."
    fi
  fi
fi

[ -z "$findings" ] && exit 0

jq -n --arg ctx "문서-코드 불일치가 감지되었다. 관련 작업을 할 때 문서를 함께 갱신하라.

${findings}" \
  '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: $ctx
    }
  }'
exit 0
