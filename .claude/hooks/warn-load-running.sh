#!/bin/bash
# PostToolUse: 적재 배치가 도는 동안 gradle을 돌렸다는 것을 알린다.
#
# 트리거가 파일이 아니라 **argv의 모양 + 지금 살아 있는 프로세스**라 hook에 있다.
# `.claude/rules/`의 glob은 파일을 열어야 발화하는데 적재는 어떤 파일도 열지 않는다.
#
# 차단(deny/ask)이 아닌 이유: 되돌릴 수 있다(잡을 다시 돌리면 된다). `harness-core.md`가
# 차단을 되돌릴 수 없는 것에만 쓰라고 하고, 게다가 `ask`는 무인 자율 실행을 막아 —
# 긴 적재는 정확히 그 상황에서 돈다 — 자기모순이다.
#
# **예방이 아니라 통보다.** `additionalContext`는 어느 이벤트를 쓰든 도구가 실행된 뒤에
# 모델에 닿으므로(`harness-hooks.md`), 이미 일어난 일을 되돌리게 하는 것이 상한이다.
# 사고 자체를 없애는 것은 `application.yaml`의 bootstrap 프로파일에서 devtools 재기동을
# 끈 쪽이고, 이 훅은 그것이 덮지 못하는 나머지를 맡는다 — `clean`은 도는 JVM의 classpath를
# 지우고, `--stop`은 데몬과 함께 포크된 적재 JVM을 끄며, 컴파일 태스크는 살아 있는
# 프로세스 밑에서 `build/classes`를 덮어쓴다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

hooks_dir="$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=lib/bash-text.sh
. "${hooks_dir}/lib/bash-text.sh"
# shellcheck source=lib/load-process.sh
. "${hooks_dir}/lib/load-process.sh"

input=$(cat)
cmd=$(echo "$input" | jq -r '.tool_input.command // empty')
session=$(echo "$input" | jq -r '.session_id // "nosession"')
agent=$(echo "$input" | jq -r '.agent_id // "main"')
[ -z "$cmd" ] && exit 0

# heredoc 본문을 걷는다. 이 저장소의 TODO.md·batch-load skill이 적재 명령을 문서로 싣고,
# 그것을 커밋 메시지에 담는 경로가 실제로 있다.
cmd=$(strip_heredoc_bodies "$cmd")

# --- gradle을 정말 "실행"하는 세그먼트만 남긴다 -----------------------------
#
# 판정을 뒤집어 놨다. 예전에는 세그먼트의 첫 토큰이 gradle인지 봤는데, 그러면 한 겹
# 감싼 형태가 전부 침묵했다 — `sh -c './gradlew build'`·`xargs -I{} ./gradlew {}`·
# `caffeinate -s ./gradlew build`(래퍼 목록에 있었는데도 `-s`에서 멈췄다)·`sudo -u x …`.
#
# MUST NOT: "gradle을 실행하는 방법"을 열거하지 않는다. 감싸는 방법은 끝이 없고 **빠뜨린
# 것이 침묵**이 된다. 대신 **이름을 부르기만 하는 argv0**을 열거하고 나머지는 실행으로 본다.
# 여기 빠뜨리면 알림이 한 번 더 뜰 뿐이라 틀리는 방향이 안전하다(`harness-hooks.md`의
# 읽기 동사 비대칭과 같은 형태).
#
# 알려진 구멍: `git bisect run ./gradlew test`는 git이 목록에 있어 침묵한다. 커밋 메시지가
# gradle 명령을 인용하는 일이 이 저장소에서 훨씬 잦아서 git을 뺄 수 없다.
gradle_segments() {
  printf '%s\n' "$1" | sed 's/&&/;/g; s/||/;/g' | tr ';|()' '\n\n\n\n' | while IFS= read -r seg; do
    [ -z "$(printf '%s' "$seg" | tr -d '[:space:]')" ] && continue

    argv0=$(printf '%s\n' "$seg" | awk '{print $1}')
    argv0=${argv0##*/}
    argv0=$(printf '%s' "$argv0" | tr -d "\"'")
    case "$argv0" in
      echo|printf|cat|grep|egrep|fgrep|rg|sed|awk|head|tail|less|more|ls|find|file|wc|sort|uniq|diff|cut|tr|column|jq|git)
        continue ;;
    esac

    # 세그먼트 어딘가에 gradle 실행 파일이 있으면 그것을 돌리는 것으로 본다.
    if printf '%s\n' "$seg" | tr ' \t' '\n\n' | sed 's|.*/||' | tr -d "\"'" \
       | grep -qxE 'gradlew|gradle'; then
      printf '%s\n' "$seg"
    fi
  done
}

segments=$(gradle_segments "$cmd")
[ -z "$segments" ] && exit 0

# --- 적재를 띄우는 세그먼트인가 ---------------------------------------------
# **세그먼트 단위로 본다.** 명령 전문에서 찾으면 `./gradlew build && git commit -m "… --job=…"`이
# "적재를 띄웠다"로 나오고, 그 오발화가 실제로 났다.
launched=false
while IFS= read -r seg; do
  [ -z "$seg" ] && continue
  case "$seg" in *bootRun*) ;; *) continue ;; esac
  case "$seg" in
    *profiles.active=bootstrap*|*--job=*) launched=true; break ;;
  esac
done <<< "$segments"

# --- 지금 살아 있는 적재 -----------------------------------------------------
set +e
pids=$(stray_load_pids)
load_rc=$?
set -e

if [ "$load_rc" -eq 2 ]; then
  # 판정하지 못했다는 것을 드러낸다. 침묵은 "확인했고 문제없다"와 구분되지 않는다.
  echo "적재가 도는 중인지 판정하지 못했다(pgrep 또는 ps가 없다). 적재 중 gradle 경고가 이 세션에서는 나오지 않는다." >&2
  exit 1
fi

marker_dir="${TMPDIR:-/tmp}/claude-harness-hooks/${session}-${agent}/warn-load-running"
already_fired() {
  local marker="${marker_dir}/$1"
  mkdir -p "$marker_dir" 2>/dev/null || return 1
  [ -f "$marker" ] && return 0
  touch "$marker" 2>/dev/null || true
  return 1
}

emit() {
  jq -n --arg ctx "$1" \
    '{hookSpecificOutput: {hookEventName: "PostToolUse", additionalContext: $ctx}}'
  exit 0
}

if [ "$launched" = true ]; then
  # 방금 띄운 것과 이미 돌고 있던 것을 나이로 가른다. 배경 실행이면 새 프로세스는 몇 초
  # 안쪽이고, 이보다 오래된 것이 있으면 그것은 **내가 띄우기 전부터** 돌던 적재다.
  # 두 적재를 90초 안에 연달아 띄우면 놓친다 — 그 창은 감수한다.
  pre_existing=""
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    age=$(load_process_age_seconds "$p") || continue
    [ "$age" -ge 90 ] && pre_existing="${pre_existing}${p} "
  done <<< "$(printf '%s\n' "$pids" | tr ' ' '\n')"

  if [ -n "$pre_existing" ]; then
    # 억제하지 않는다. 중복 기동은 매번 별개 사고다.
    emit "**이미 적재가 돌고 있는데 하나를 더 띄웠다(먼저 돌던 PID ${pre_existing%% }).** 단일 실행 판정은 \`AdminJobLauncher\`의 인메모리 집합이라 **JVM을 가로지르는 중복을 원리적으로 막지 못한다** — 두 적재가 같은 테이블에 동시에 쓰고, 나중 것이 먼저 것의 \`batch_execution\` 행을 정리 대상으로 볼 수도 있다. 하나를 끄고 하나만 돌린다. **PID를 특정해서 끈다** — \`pkill -f java\`는 운영 앱을 함께 죽인다."
  fi

  # 띄운 그 호출에서 아래 분기 B까지 함께 내지 않는다. 방금 뜬 프로세스를 "네가 방해했다"고
  # 말하게 된다.
  already_fired launched || emit '적재 배치를 띄웠다. **이것이 사는 동안 gradle 태스크를 돌리지 않는다** — 컴파일 산출물이 도는 JVM의 classpath를 덮고, `clean`은 그것을 지우며, `--stop`은 포크된 적재 JVM을 함께 끈다. 예외는 `./gradlew checkDuplicateFiles` 하나다(단독 실행 시 아무것도 컴파일하지 않는다). 끝나면 러너가 스스로 내려간다 — 끊고 싶으면 PID를 특정해 끈다. **MUST NOT: `pkill -f java`류를 쓰지 않는다** — 운영 앱이 함께 죽고 어떤 가드도 그것을 막지 않는다.'
  exit 0
fi

# --- 적재가 도는데 gradle을 돌렸다 ------------------------------------------
[ "$load_rc" -ne 0 ] && exit 0

# 컴파일하는 태스크를 열거하지 않는다. `processResources`가 `frontendBuild`에 걸려 있어
# 프론트 경유 태스크까지 classpath를 건드리므로 열거로는 덮이지 않고, 빠뜨린 것이 침묵이 된다.
# **예외는 하나뿐이다** — `checkDuplicateFiles`는 `dependsOn`이 없고 단독 실행 시
# `mustRunAfter testClasses`가 무시되어 아무것도 컴파일하지 않는다(`--dry-run`으로 확인했다).
# 적재 창 안에서도 커밋 전 복제본 확인을 지킬 수 있어야 하므로 이것만 통과시킨다.
#
# 태스크 이름의 모양으로 좁힌다. "`-`로 시작하지 않는 토큰"으로 잡으면 리다이렉션이 태스크로
# 읽혀(`2>&1`) 예외가 무너진다 — 실제로 이 훅이 자기 예외에 걸려 오발화했다.
only_safe=true
while IFS= read -r seg; do
  [ -z "$seg" ] && continue
  tasks=$(printf '%s\n' "$seg" | awk '{for (i = 2; i <= NF; i++) if ($i ~ /^[A-Za-z:][A-Za-z0-9_:.-]*$/) print $i}')
  [ -z "$tasks" ] && { only_safe=false; break; }
  while IFS= read -r t; do
    [ "$t" = "checkDuplicateFiles" ] || { only_safe=false; break 2; }
  done <<< "$tasks"
done <<< "$segments"
[ "$only_safe" = true ] && exit 0

# 억제하지 않는다. 발화 하나가 재실행 하나에 대응한다.
emit "**적재 배치가 돌고 있다(PID ${pids}).** 방금 돌린 gradle이 그 프로세스의 classpath를 건드렸으면 러너는 처음부터 다시 간다 — 화면에 단서가 없고 \`batch_execution\`에 새 실행 행으로만 남는다. 지금 확인할 것: 로그에 \"부트스트랩 러너를 시작한다\"가 몇 건인가. 적재를 끝까지 돌릴 생각이면 그것이 끝날 때까지 gradle을 멈추고, 검증이 급하면 적재를 먼저 끈다(PID를 특정해서 — \`pkill -f java\`는 운영 앱을 함께 죽인다)."
