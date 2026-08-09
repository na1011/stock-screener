#!/bin/bash
# SessionStart guard: 문서의 시점 의존적 주장과 실제 상태(코드 또는 다른 정본)를 대조해
# 불일치만 보고한다. 루트의 사람용 문서는 대조 대상이 아니다(`.claude/rules/harness.md`).
# 차단하지 않는다. 불일치가 없으면 아무것도 출력하지 않고 조용히 통과한다.
set -euo pipefail

# jq가 없으면 이 검사만 죽는 게 아니라 guard-secrets.sh의 시크릿 판정도 함께 죽는다.
# 그 사실을 먼저 알린다.
#
# 여기서 평문 stdout을 쓰는 이유는 하나뿐이다 — jq에 의존하는 경로로는 jq 부재를 알릴 수 없다.
# SessionStart의 평문 stdout은 그대로 컨텍스트로 들어가므로 jq가 필요 없다.
# 아래 findings 보고가 jq -n을 쓰는 것과 달라 보이지만, 그쪽은 jq가 있을 때만 도달한다.
#
# MUST NOT: 죽는 훅을 이름으로 열거하지 않는다. 그 열거가 곧 낡는 목록이 되고, 낡은 목록은
# "덮여 있다"고 믿게 만든다 — 이 검사가 잡으려는 병과 같은 병이다. 등록 목록의 정본은
# `.claude/settings.json`이다.
if ! command -v jq >/dev/null 2>&1; then
  echo "jq가 없다. jq에 의존하는 훅이 전부 판정을 못 하는 상태다 — 차단 가드와 알림 계층 양쪽이고, 어느 것이 걸리는지는 .claude/settings.json의 등록 목록이 답한다. 특히 .env.production·*.key·credentials.json 같은 경로는 permissions가 덮지 않아 지금 아무것도 막지 않는다. jq를 설치하면 복구된다."
  exit 0
fi

# 이 훅이 중앙 보고자다. 자기 죽음을 알릴 수 있는 것은 자기밖에 없으므로 조용히 나가지 않는다.
root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || {
  echo "문서 낡음 검사를 돌리지 못했다: 프로젝트 루트($root)로 이동할 수 없다. 이 세션에서는 문서-코드 불일치가 보고되지 않는다."
  exit 0
}

findings=""
add() { findings="${findings}- $1"$'\n'; }

# 0. Bash 훅 공용 파일이 있는가
#
# `guard-destructive.sh`·`warn-bootstrap-args.sh`·`remind-rule-on-shell.sh`가 이 파일을
# source한다. 없으면 `set -e`에서 셋 다 죽는다 — 죽은 훅은 Bash 호출마다 stderr를 내므로
# 침묵은 아니지만, **파괴적 명령 차단이 사라진 상태**라 세션 시작에 한 번 못 박아 둔다.
# jq 부재를 위에서 중앙 경보로 다루는 것과 같은 이유다.
if [ ! -r .claude/hooks/lib/bash-text.sh ]; then
  add "\`.claude/hooks/lib/bash-text.sh\`가 없다. Bash 훅 셋이 이것을 source하므로 지금 파괴적 명령 차단(rm -rf·force push·DROP TABLE)과 시크릿 읽기 확인이 모두 죽어 있다. 복구 전에는 그 명령들을 직접 검토해라."
fi

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
    # 경고선을 절단선의 절반에 둔다. 둘이 같은 지점이면 이미 손상된 뒤에야 울린다 —
    # 그때는 뒷부분이 이번 세션에 전달되지 않은 상태이고, 무엇이 잘렸는지도 모른다.
    # 별도 상수를 두지 않고 절단선에서 계산해, 한쪽만 바뀌는 조합을 애초에 만들지 않는다.
    warn_limit=$(( inject_limit / 2 ))
    len=$(wc -m < docs/progress.md 2>/dev/null | tr -d ' ')
    if [ -n "$len" ] && [ "$len" -gt "$inject_limit" ]; then
      add "docs/progress.md가 ${len}자로 주입 상한(${inject_limit}자)을 넘는다. 세션 시작 주입이 이미 잘렸고, 뒷부분은 이번 세션에 전달되지 않았다. 파일을 직접 읽고 줄여라."
    elif [ -n "$len" ] && [ "$len" -gt "$warn_limit" ]; then
      add "docs/progress.md가 ${len}자로 경고선(${warn_limit}자)을 넘었다. 절단은 ${inject_limit}자부터다. 줄을 더하기 전에 뺄 것을 먼저 정하라 — 고정 예산이지 로그가 아니다. 파일별 함정은 \`.claude/rules/\`로, 작업 절차는 skill로 내린다."
    fi
  fi
fi

# 1b. 훅에 문법 오류가 있는가
#
# `PreToolUse` 훅의 종료 코드 2는 **도구 호출을 차단한다.** bash 문법 오류의 종료 코드가
# 정확히 2라, 알림 훅의 오타 하나가 모든 편집을 막는다 — fail-open 규약이 통째로 뒤집히는
# 유일한 경로다. 그 상태는 "왜 갑자기 아무것도 안 되지"로만 보이고 원인이 드러나지 않는다.
if command -v bash >/dev/null 2>&1; then
  broken=""
  while IFS= read -r h; do
    [ -z "$h" ] && continue
    bash -n "$h" 2>/dev/null || broken="${broken}$(basename "$h") "
  done <<< "$(find .claude/hooks -name '*.sh' -type f 2>/dev/null)"
  if [ -n "$broken" ]; then
    add "문법 오류가 있는 훅이 있다: ${broken}— \`PreToolUse\`에 걸려 있으면 종료 코드 2가 **도구 호출을 차단한다.** \`bash -n\`으로 확인하고 고쳐라."
  fi
fi

# 1c. 규칙 파일이 줄기만 하고 준 적이 없는가
#
# 절대 예산선을 쓰지 않는다 — 규칙 주입의 크기 상한이 측정된 적이 없어 어떤 숫자도 근거가
# 없고, 근거 없는 경고선은 오탐이 되며 오탐이 반복되면 이 검사 자체가 무시된다.
# 대신 실제로 일어난 병의 형태를 그대로 잡는다: `harness.md`는 11커밋 내내 순증가였고
# 감소 커밋이 0회였다. 그동안 아무도 그것을 알리지 않았다.
#
# 줄이 아니라 **문자**로 센다. 한글 산문은 줄 수가 유지된 채 문장만 줄어드는 편집이 흔해
# `--numstat`의 줄 기준으로는 감소가 보이지 않는다(§1이 `wc -m`을 쓰는 것과 같은 이유).
# 파일이 생긴 커밋은 제외한다 — 안 하면 새 규칙 파일이 첫날부터 울린다.
if command -v git >/dev/null 2>&1 && [ -d .claude/rules ]; then
  window=12
  min_points=4      # 이보다 이력이 짧으면 추세라고 부를 수 없다
  min_growth=800
  growing=""
  for rule in .claude/rules/*.md; do
    [ -f "$rule" ] || continue
    # 오래된 순으로 뒤집는다. `tac`·`tail -r`은 플랫폼마다 갈리므로 sed로 한다.
    revs=$(git log --format=%H -n "$window" -- "$rule" 2>/dev/null | sed '1!G;h;$!d')
    n=$(printf '%s\n' "$revs" | grep -c . || true)
    [ "${n:-0}" -eq 0 ] && continue
    # 파일이 이 창 안에서 생겼으면 **생성 커밋만** 뺀다. 창을 다 채울 때까지 기다리면,
    # 11커밋 동안 한 번도 줄지 않은 채 2.8배가 된 파일이 끝내 발화하지 않는다.
    total=$(git log --format=%H -- "$rule" 2>/dev/null | grep -c . || true)
    if [ "${total:-0}" -le "$window" ]; then
      revs=$(printf '%s\n' "$revs" | tail -n +2)
      n=$((n - 1))
    fi
    [ "$n" -lt "$min_points" ] && continue

    shrank=0; prev=""; first=""; last=""
    while IFS= read -r rev; do
      [ -z "$rev" ] && continue
      cur=$(git show "${rev}:${rule}" 2>/dev/null | wc -m | tr -d ' ')
      [ -z "$cur" ] || [ "$cur" -eq 0 ] && continue
      [ -z "$first" ] && first="$cur"
      last="$cur"
      # 감소한 커밋이 한 번이라도 있으면 조용하다. 늘었다 줄었다 하는 것은 정상이다.
      [ -n "$prev" ] && [ "$cur" -lt "$prev" ] && shrank=1
      prev="$cur"
    done <<< "$revs"

    [ -z "$first" ] || [ -z "$last" ] && continue
    [ "$shrank" -eq 1 ] && continue
    [ $((last - first)) -lt "$min_growth" ] && continue
    growing="${growing}$(basename "$rule")(+$((last - first))자) "
  done
  if [ -n "$growing" ]; then
    add "지난 ${window}커밋 동안 줄어든 적이 없는 규칙 파일이 있다: ${growing}— 규칙 파일은 고정 예산이지 로그가 아니다. 다음에 더할 때 뺄 것을 함께 정하고, 규칙이 아니라 그것을 알게 된 경위가 쌓인 것은 아닌지 본다."
  fi
fi

# 2. 프론트엔드 부재 주장 vs 실제
if [ -d frontend ] || [ -d src/main/frontend ] || [ -d src/main/resources/static/src ] \
   || [ -n "$(find . -name '*.vue' -not -path './.git/*' -not -path './build/*' -print -quit 2>/dev/null)" ]; then
  # 루트의 사람용 문서는 보지 않는다 — 이 훅의 대조 대상이 아니다(`harness.md`).
  if grep -qsE '프론트엔드 (디렉터리|디렉토리)?( 없|.*아직)' docs/progress.md; then
    add "진행 상태가 프론트엔드 부재를 주장하지만 실제 프론트엔드 파일·디렉터리가 있다."
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

# 4. 규칙의 paths와 ADR의 applies_to glob이 실제 파일과 매칭되는가
#
# 매칭되지 않는 glob은 규칙을 **영원히 로드되지 않게** 만들고, 로드되지 않았다는 사실은
# 어디에도 드러나지 않는다. 실제로 frontend.md의 `**/*.js`·`**/*.scss`가 그 상태였다.
#
# **ADR도 같은 성질이다.** `applies_to`가 아무것도 매칭하지 않으면 `remind-adr.sh`의 참조
# 방향이 그 ADR에 대해 영구히 침묵한다 — 위 3번은 필드의 **존재**만 보므로 통과한다.
# 근거 계층이 그 ADR에 대해 죽는 것이고, 죽었다는 신호가 없다.
#
# 파일 단위로 본다 — 어느 glob이든 하나 매칭되면 통과다. glob 단위로 가면
# `application-*.yaml`처럼 오늘 0건이지만 내일 생길 선행 glob에서 첫날부터 오탐이 나고,
# 오탐이 반복되면 이 검사 자체가 무시된다.
#
# `**`를 `*`로 접는다. bash `[[ ]]` 패턴에서 `*`는 `/`를 넘으므로 `src/main/java/**`가
# 의도대로 동작한다. IMPORTANT — **이 접기는 bash 전용이다.** 같은 대조를 zsh에서 손으로
# 흉내내면 `*`가 `/`를 넘지 않아 0건이 나온다(2026-08-09 실측: bash 47건 / zsh 0건). 그 결과를
# 믿고 멀쩡한 glob을 "고치러" 갈 수 있다. 손으로 확인할 때는 `git ls-files '<glob>'`을 쓴다.
if command -v git >/dev/null 2>&1; then
  tracked=$(git ls-files 2>/dev/null || true)
  if [ -n "$tracked" ]; then
    # 첫 인자는 목록 키(`paths:` 또는 `applies_to:`), 나머지는 검사할 파일들.
    # 매칭 실패한 파일 이름을 공백으로 이어 stdout에 낸다.
    collect_orphans() {
      local key="$1"; shift
      local doc globs matched pat f out=""
      for doc in "$@"; do
        [ -f "$doc" ] || continue
        globs=$(awk -v k="^${key}:[[:space:]]*$" '
          $0 ~ k { f = 1; next }
          f && /^[[:space:]]*-[[:space:]]*/ {
            sub(/^[[:space:]]*-[[:space:]]*/, "")
            gsub(/^["'"'"']|["'"'"']$/, "")
            gsub(/\*\*/, "*")
            # frontmatter 종료선 `---`도 목록 항목처럼 보인다. 접힌 뒤 `--`가 되는데
            # 어느 파일과도 매칭되지 않아 무해하지만, 세어서 헷갈릴 이유가 없다.
            if ($0 != "" && $0 != "-" && $0 != "--") print
            next
          }
          f { exit }
        ' "$doc" 2>/dev/null || true)
        # 목록이 없으면 이 검사 대상이 아니다 — 규칙은 매 세션 로드되고,
        # ADR의 필드 부재는 위 3번이 이미 보고한다.
        [ -z "$globs" ] && continue
        matched=false
        while IFS= read -r pat; do
          [ -z "$pat" ] && continue
          while IFS= read -r f; do
            # shellcheck disable=SC2053  # 우변을 따옴표로 감싸면 glob이 아니라 리터럴이 된다
            if [[ "$f" == $pat ]]; then matched=true; break; fi
          done <<< "$tracked"
          [ "$matched" = true ] && break
        done <<< "$globs"
        [ "$matched" = false ] && out="${out}$(basename "$doc") "
      done
      printf '%s' "$out"
    }

    if [ -d .claude/rules ]; then
      orphan_rules=$(collect_orphans paths .claude/rules/*.md)
      if [ -n "$orphan_rules" ]; then
        add "paths glob이 실제 파일과 하나도 매칭되지 않는 규칙이 있다: ${orphan_rules}— 이 규칙은 영원히 로드되지 않는다. \`git ls-files '<glob>'\`으로 glob을 맞춰라."
      fi
    fi

    if [ -d docs/adr ]; then
      orphan_adrs=$(collect_orphans applies_to docs/adr/ADR-[0-9][0-9][0-9][0-9]-*.md)
      if [ -n "$orphan_adrs" ]; then
        # "깨졌다"고 단정하지 않는다. 아직 만들지 않은 패키지를 가리키는 것은 정상일 수 있다
        # (ADR-0010처럼 미룬 결정). 다만 그동안 포인터가 침묵한다는 사실은 알아야 한다.
        add "applies_to가 지금 어떤 파일과도 매칭되지 않는 ADR이 있다: ${orphan_adrs}— 그 코드가 생길 때까지 \`remind-adr.sh\`의 포인터는 이 ADR에 대해 침묵한다. 예정된 경로면 정상이고, 오타·이름 변경이면 지금 고쳐라. 영구히 매칭될 수 없는 glob이면 아무것도 하지 않으므로 지운다."
      fi
    fi
  fi
fi

# 5. 트리거가 서로 닿지 않아 이중화할 수밖에 없는 사실이 어긋났는가
#
# 두 기제가 서로를 발화시키지 못하면 사본을 없앨 수 없다 — 없애면 한쪽 세션이 그 함정을
# 영영 못 본다. 없앨 수 없으면 어긋남을 잡는다(ADR-0020이 백엔드·프론트 거울에 쓴 형태).
#
# 표에 `추출정규식|파일A|파일B|이름` 한 줄을 더하면 등록된다. 줄바꿈 위치가 파일마다 달라
# 줄 단위로는 못 비교하므로 개행을 접고 문장만 뽑아 대조한다.
# 정규식에 역따옴표를 쓰지 않는다 — 큰따옴표 안에서 명령 치환으로 읽힌다. `.`로 대신한다.
mirrors="공공데이터포털은 없는 종목[^)]*)|.claude/rules/data-pipeline.md|.claude/skills/batch-load/SKILL.md|공공데이터포털 응답 모양
ADJ_CLOSE.는 봉 하나로.*FK로 정리가 중단된다\.|.claude/rules/data-pipeline.md|.claude/rules/test.md|ADJ_CLOSE FK 함정"

extract_mirror() {  # $1=파일 $2=정규식
  tr '\n' ' ' < "$1" | tr -s ' ' | sed -n "s/.*\($2\).*/\1/p"
}
while IFS='|' read -r pat fa fb label; do
  [ -z "$pat" ] && continue
  [ -f "$fa" ] && [ -f "$fb" ] || continue
  ma=$(extract_mirror "$fa" "$pat")
  mb=$(extract_mirror "$fb" "$pat")
  if [ -z "$ma" ] || [ -z "$mb" ]; then
    add "${label}의 사본이 한쪽에서 사라졌다(${fa}=$([ -n "$ma" ] && echo 있음 || echo 없음), ${fb}=$([ -n "$mb" ] && echo 있음 || echo 없음)) — 두 트리거가 서로 닿지 않아 한쪽만으로는 그 세션이 이 함정을 못 본다."
  elif [ "$ma" != "$mb" ]; then
    add "${label}이 ${fa}와 ${fb}에서 갈렸다 — 거울은 어긋나도 아무 데서도 티가 나지 않는다. 두 문장을 다시 같게 맞춰라."
  fi
done <<< "$mirrors"

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
