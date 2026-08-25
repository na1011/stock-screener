#!/bin/bash
# SessionStart: 진행 상태(docs/progress.md)를 세션 시작 시 주입한다.
#
# 저장소에 파일이 있는 것만으로는 부족하다. "다음 세션이 알아서 찾아 읽겠지"에 기대면
# 실제로는 읽지 않는다 — 새 세션은 무엇을 모르는지조차 모르기 때문이다.
# additionalContext는 첫 프롬프트 이전에 주입되므로 확실하다.
#
# 차단하지 않는다. 다만 **조용히 실패하지도 않는다** — 주입이 죽으면 다음 세션이 눈먼 채
# 시작하는데, 그것이 이 훅이 막으려던 바로 그 상태다. 실패는 평문 stdout으로 알린다
# (`SessionStart`의 평문 stdout은 그대로 컨텍스트가 되고 jq가 필요 없다).
# 수단을 이벤트별로 가르는 근거는 `.claude/rules/harness-hooks.md`에 있다.
set -euo pipefail

# 실패를 알리고 나간다. 종료코드가 아니라 이 문구가 신호다.
bail() {
  echo "진행 상태 주입에 실패했다: $1 다음 세션은 docs/progress.md를 직접 읽어야 한다."
  exit 0
}

hooks_dir="$(dirname "${BASH_SOURCE[0]}")"

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || bail "프로젝트 루트($root)로 이동할 수 없다."

# --- 지금 돌고 있는 적재 -----------------------------------------------------
# 문서가 아니라 **이 순간의 기계 상태**라 progress.md보다 앞에 둔다. 세션이 이것을 모르면
# gradle을 돌려 적재를 처음으로 되돌리고(실제로 5회 그랬다), 남은 커넥션이 전제 검사를
# 무판정으로 만든 것을 부하로 오진한다.
#
# `harness-hooks.md`가 정한 배치는 "상태 → SessionStart 주입"이고 그 기제가 이 파일이다.
#
# 다른 훅과 달리 방어적으로 source한다. 여기서 죽으면 **진행 상태 주입 자체가 사라지는데**,
# 그것이 이 훅이 막으려던 바로 그 상태다. 부재 보고는 check-doc-staleness.sh가 맡는다.
load_note=""
if [ -r "${hooks_dir}/lib/load-process.sh" ]; then
  # shellcheck source=lib/load-process.sh
  . "${hooks_dir}/lib/load-process.sh"
  set +e
  load_pids=$(stray_load_pids)
  load_rc=$?
  set -e
  if [ "$load_rc" -eq 0 ]; then
    load_note="**적재 배치가 지금 돌고 있다(PID ${load_pids}).** 이 세션에서 gradle 태스크를 돌리면 그 프로세스의 classpath를 건드려 러너가 처음부터 다시 갈 수 있다 — 무엇이 예외인지는 실제로 돌렸을 때 \`warn-load-running.sh\`가 답한다. 끝났어야 할 프로세스라면 지금 끈다 — 남아 있는 커넥션 풀이 \`check-premises.sh\`를 무판정으로 만들고, 그 무판정은 부하로 오진되기 쉽다. **PID를 특정해서 끈다. \`pkill -f java\`는 운영 앱을 함께 죽인다.**

"
  elif [ "$load_rc" -eq 2 ]; then
    load_note="적재가 도는 중인지 판정하지 못했다(pgrep 또는 ps가 없다). 이 세션에서는 적재 관련 경고가 나오지 않는다.

"
  fi
fi

progress="docs/progress.md"
[ -f "$progress" ] || bail "$progress 파일이 없다."

content=$(cat "$progress" 2>/dev/null) || bail "${progress}를 읽지 못했다."
[ -n "$content" ] || bail "${progress}가 비어 있다."

# --- 백로그 절은 주입하지 않는다 -------------------------------------------
# 상태·제약은 **모르면 실수하므로** 주입해야 하고, 백로그는 **모르면 고를 뿐이라** 주입할
# 필요가 없다. 그런데 그것이 파일의 절반 가까이이고 매 세션 실린다. 파일은 안 가른다 —
# 갈라서 주입 밖에 두면 안 읽혀 낡는다(이 저장소의 `TODO.md`가 그 전례다).
#
# MUST NOT: 여기서 `bail`하지 않는다. `bail`은 주입을 통째로 포기하는데, 그것이 이 훅이
# 막으려는 바로 그 상태다. 못 가르면 **전부 주입하고 그 사실을 낸다** — 커진 주입은 아래
# 절단 경고로 드러나지만, 조용히 다 빼면 백로그가 사라진 것을 아무도 모른다.
backlog_note=""
if [ -r "${hooks_dir}/lib/progress-section.sh" ]; then
  # shellcheck source=lib/progress-section.sh
  . "${hooks_dir}/lib/progress-section.sh"
  if progress_has_section "$progress"; then
    kept=$(progress_kept "$progress")
    n_backlog=$(progress_excluded_items "$progress")
    if [ -n "$kept" ]; then
      content="$kept"
      backlog_note="**「바로 착수 가능한 것」 ${n_backlog}개는 주입에서 뺐다** — 일을 고를 때 \`${progress}\`를 열어라. 상태·제약·사용자 판단 대기는 아래에 그대로 있다.

"
    else
      backlog_note="**「바로 착수 가능한 것」 절을 뺐더니 남는 것이 없어 전부 주입했다** — 문서가 그 절 하나뿐인 상태다.

"
    fi
  else
    backlog_note="**「바로 착수 가능한 것」 절을 못 찾아 전부 주입했다** — 절 제목이 바뀌었으면 \`.claude/hooks/lib/progress-section.sh\`의 이름도 함께 고친다.

"
  fi
else
  backlog_note="**\`lib/progress-section.sh\`가 없어 필터 없이 전부 주입했다.**

"
fi

# jq는 아래 출력에만 필요하다. 없으면 내용을 그대로 stdout으로 낸다 —
# 잘려서라도 컨텍스트에 닿는 것이 아무것도 안 하는 것보다 낫다.
if ! command -v jq >/dev/null 2>&1; then
  echo "jq가 없어 구조화 주입을 건너뛴다. 아래가 docs/progress.md 원문이다."
  echo
  [ -n "$load_note" ] && printf '%s' "$load_note"
  [ -n "$backlog_note" ] && printf '%s' "$backlog_note"
  echo "$content"
  exit 0
fi

# additionalContext는 10,000자 상한이다. 안내 문구 몫을 빼고 여유를 둔다.
# 넘치면 잘라내되 잘렸다는 사실을 반드시 알린다 — 조용히 자르면 그 자체가 무성실패이고,
# 에이전트는 잘린 문서를 완전한 것으로 믿는다.
limit=8000
truncated=""
if [ "${#content}" -gt "$limit" ]; then
  content="${content:0:$limit}"
  truncated="

---
IMPORTANT: docs/progress.md가 길어 위 내용은 앞부분만 주입되었다. 전체를 보려면 파일을 직접 읽어라.
그리고 이 파일을 한 페이지로 줄여라 — 길어지면 주입이 잘리고 신호가 묻힌다."
fi

jq -n --arg ctx "${load_note}다음은 이 프로젝트의 현재 진행 상태다 (\`docs/progress.md\`). 작업을 시작하기 전에 이것을 기준으로 삼아라.

${backlog_note}${content}${truncated}" \
  '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: $ctx
    }
  }' || bail "jq가 주입 페이로드를 만들지 못했다."
exit 0
