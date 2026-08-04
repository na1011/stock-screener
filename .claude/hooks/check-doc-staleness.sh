#!/bin/bash
# SessionStart guard: 문서의 시점 의존적 주장과 실제 코드 상태를 대조해 불일치만 보고한다.
# 차단하지 않는다. 불일치가 없으면 아무것도 출력하지 않고 조용히 통과한다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || exit 0

findings=""
add() { findings="${findings}- $1"$'\n'; }

# 1. CLAUDE.md가 "의존성이 아직 없다"고 주장하는데 실제로 추가되었는가
if [ -f CLAUDE.md ] && [ -f build.gradle ]; then
  if grep -qE '아직 .*(없|불필요)' CLAUDE.md; then
    present=""
    grep -qiE '\bjpa\b|spring-boot-starter-data-jpa' build.gradle && present="${present}JPA "
    grep -qi 'querydsl'   build.gradle && present="${present}QueryDSL "
    grep -qi 'postgresql' build.gradle && present="${present}PostgreSQL "
    grep -qiE 'oauth2|spring-boot-starter-security' build.gradle && present="${present}OAuth "
    grep -qi 'sentry'     build.gradle && present="${present}Sentry "
    if [ -n "$present" ]; then
      add "CLAUDE.md의 '현재 상태' 절이 의존성 부재를 주장하지만 build.gradle에 이미 있다: ${present}"
    fi
  fi
fi

# 2. 프론트엔드 부재 주장 vs 실제
if [ -d frontend ] || [ -d src/main/frontend ] || [ -d src/main/resources/static/src ] \
   || [ -n "$(find . -name '*.vue' -not -path './.git/*' -not -path './build/*' -print -quit 2>/dev/null)" ]; then
  if grep -qsE '프론트엔드 (디렉터리|디렉토리)?.*아직' CLAUDE.md README.md; then
    add "CLAUDE.md/README.md가 프론트엔드 부재를 주장하지만 실제 프론트엔드 파일·디렉터리가 있다."
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

  # 기록 목록을 수동으로 유지하는 흔적이 남아 있으면 낡는다.
  if [ -f docs/adr/README.md ] && grep -q '아직 없음' docs/adr/README.md; then
    adr_count=$(find docs/adr -maxdepth 1 -name 'ADR-[0-9][0-9][0-9][0-9]-*.md' \
                  2>/dev/null | wc -l | tr -d ' ')
    [ "$adr_count" -gt 0 ] && add "ADR이 ${adr_count}건 있으나 docs/adr/README.md는 '(아직 없음)'이라고 적고 있다."
  fi
fi

# 4. data-pipeline.md의 paths glob이 실제 패키지와 매칭되는가 (패키지가 생긴 뒤에만 검사)
rules="./.claude/rules/data-pipeline.md"
if [ -f "$rules" ] && [ -d src/main/java ]; then
  pkg_count=$(find src/main/java -mindepth 3 -type d 2>/dev/null | wc -l | tr -d ' ')
  if [ "$pkg_count" -gt 0 ]; then
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
