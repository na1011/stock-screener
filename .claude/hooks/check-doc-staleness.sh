#!/bin/bash
# SessionStart guard: 문서의 시점 의존적 주장과 실제 코드 상태를 대조해 불일치만 보고한다.
# 차단하지 않는다. 불일치가 없으면 아무것도 출력하지 않고 조용히 통과한다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || exit 0

findings=""
add() { findings="${findings}- $1"$'\n'; }

# 1. progress.md가 "Phase 1 미착수/골격만"이라고 주장하는데 실제로 의존성·도메인 코드가 생겼는가
#
# Phase 1 행만 본다. "미착수"를 문서 전체에서 찾으면 아직 시작하지 않은 뒤 phase들이
# 매번 걸려 오탐이 된다. 매 세션 같은 경고가 뜨면 신호가 무뎌져, 정작 진짜 불일치가
# 생겼을 때 무시하게 된다 — 이 검사가 막으려던 실패 모드 그 자체다.
if [ -f docs/progress.md ]; then
  phase1_line=$(grep -E '^\|\s*1\.' docs/progress.md 2>/dev/null | head -1)
  if printf '%s' "$phase1_line" | grep -qE '미착수' \
     || grep -qE '골격만 있다|도메인 코드 없음' docs/progress.md; then
    present=""
    if [ -f build.gradle ]; then
      grep -qiE '\bjpa\b|spring-boot-starter-data-jpa' build.gradle && present="${present}JPA "
      grep -qi 'querydsl'   build.gradle && present="${present}QueryDSL "
      grep -qi 'postgresql' build.gradle && present="${present}PostgreSQL "
      grep -qiE 'oauth2|spring-boot-starter-security' build.gradle && present="${present}OAuth "
      grep -qi 'sentry'     build.gradle && present="${present}Sentry "
    fi
    domain=$(find src/main/java -name '*.java' \
               -not -name '*Application.java' \
               -not -name '*ApplicationTests.java' \
               -print -quit 2>/dev/null)
    [ -n "$domain" ] && present="${present}도메인클래스 "
    if [ -n "$present" ]; then
      add "docs/progress.md가 골격/미착수 상태를 주장하지만 실제로는 이미 있다: ${present}— 진행 상태를 갱신하라. 낡은 진행 상태는 다음 세션을 잘못 인도한다."
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
