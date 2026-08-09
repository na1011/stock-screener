#!/bin/bash
# SessionStart: ADR의 `## 검증`에 ```premise 로 태그된 명령을 돌려, 그 결정이 기대는 전제가
# **아직 참인지** 묻는다. 태그되지 않은 펜스는 건드리지 않는다.
#
# 왜 필요한가 — ADR 36개가 전부 `## 검증`을 갖고 실행 가능한 명령이 그 안에 적혀 있는데
# 아무도 돌리지 않았다. 그동안 결정의 전제가 조용히 무너졌다: 백분위가 유니버스를 가로질러
# 매겨졌고, 지표 갱신이 2억 3천만 건 쌓였고, 시드 유니버스가 문서와 반대 상태였다. 셋 다
# 컴파일되고 테스트도 통과하는 상태였다.
#
# 이 훅은 **재료만 낸다.** 무엇을 하라고 말하지 않고, 아무것도 막지 않는다.
#
# 계약이 넷이다. **침묵이 절대로 "건강"을 뜻하지 않게 하는 것**이 요점이다 —
#   stdout이 OK로 시작  → 전제가 참. 침묵한다(뒤에 잰 값이 붙는다)
#   그 외의 출력        → 발견. 라벨과 함께 보고한다
#   출력이 없다         → **판정 불가.** 모집단이 비면 SQL이 0행이고, 그것을 "참"으로 읽으면
#                          이 훅이 잡으려는 병에 이 훅이 걸린다
#   종료 코드 ≠ 0       → 판정 불가. stderr를 사유로 싣는다
#
# MUST: 전제 SQL이 모집단을 스스로 단정하게 쓴다. 계약만으로는 "0행"과 "정상 0건"을 가를 수
# 없다. `0000-template.md`에 그 형태가 있다.
set -euo pipefail

# --- 의존성 ---------------------------------------------------------------
# jq가 없으면 **돌리지 않는다.** 아래에서 guard-destructive.sh로 본문을 심사하는데 그것이
# jq에 의존하기 때문이다. 심사 없이 실행하면 문서가 무검열 실행 표면이 된다.
# 부재 경보 자체는 check-doc-staleness.sh가 세션 시작에 한 번 낸다.
command -v jq >/dev/null 2>&1 || exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || exit 0
[ -d docs/adr ] || exit 0

guard="$root/.claude/hooks/guard-destructive.sh"

# 전제 하나당 상한(초)과 전체 예산(초).
# macOS 기본에 timeout·gtimeout이 둘 다 없다. perl의 alarm이 유일하게 있는 수단이다.
#
# MUST: `per_limit > PGCONNECT_TIMEOUT + statement_timeout`을 유지한다(8 > 4 + 3).
# 부등호가 뒤집히면 psql이 자기 오류 문자열을 내기 전에 alarm이 먼저 프로세스를 죽여,
# "왜 못 쟀는가"가 "5초 안에 끝나지 않았다"로 뭉개진다 — 접속 실패와 질의 지연과 DB
# 다운이 한 문장으로 합쳐진다.
#
# MUST: `total_budget + per_limit < settings.json의 훅 timeout`을 유지한다(20 + 8 < 30).
# 예산은 새 전제를 **시작할 때** 검사하므로 최악은 total_budget을 다 쓴 뒤 마지막 하나가
# per_limit을 다 쓰는 경우다. 훅이 timeout에 걸리면 **출력이 통째로 사라지고**, 이 훅에게
# 침묵은 가장 나쁜 결과다.
per_limit=8
total_budget=20

# psql이 읽기 전용으로 붙게 한다. 러너가 도메인을 아는 유일한 지점이고, 무엇을 재는지
# 해석하는 것이 아니라 **되돌릴 수 없는 일을 못 하게** 하는 방어다. psql이 아닌 전제에는
# 그냥 무시되는 환경변수다.
export PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=3s'
# 2초로는 적재가 도는 동안 접속이 못 붙어 무판정이 났다. 슬롯 고갈이 아니라 CPU 포화다 —
# 적재 JVM과 빌드가 함께 돌면 SCRAM 인증과 프로세스 fork가 실제로 느려진다. 그래서 값을
# 올리는 것이 맞는 처방이고, 위 per_limit 부등식이 그 여유를 감당한다.
#
# **다만 무판정의 원인이 언제나 이것은 아니다.** 끝났어야 할 적재 프로세스가 살아 있어도
# 같은 증상이 나오고, 그때는 값을 아무리 올려도 낫지 않는다 — 실제로 그 상황에서 이 값을
# 올린 적이 있다. 그래서 아래 보고가 "적재가 돌고 있다"를 원인 후보로 함께 낸다.
# MUST: 이 값을 다시 올리기 전에 그 줄이 떴는지부터 본다.
export PGCONNECT_TIMEOUT=4

findings=""
unknowns=""
n_findings=0
n_unknowns=0
n_ok=0

add_finding() { findings="${findings}- ${1}"$'\n'; n_findings=$((n_findings + 1)); }
add_unknown() { unknowns="${unknowns}- ${1}"$'\n'; n_unknowns=$((n_unknowns + 1)); }

# --- 본문 심사 ------------------------------------------------------------
# 새 판정기를 만들지 않는다. "무엇이 파괴적인가"에 두 번째 답을 두면 둘이 갈리고,
# 갈린 사실은 파괴적 명령이 실제로 도는 날에야 드러난다.
vetoed() {
  local body="$1" verdict=""
  [ -x "$guard" ] || return 0   # 심사할 수 없으면 돌리지 않는다
  verdict=$(printf '%s' "$(jq -n --arg c "$body" '{tool_input:{command:$c}}')" \
    | "$guard" 2>/dev/null | jq -r '.hookSpecificOutput.permissionDecision // empty' 2>/dev/null) || verdict=""
  [ -n "$verdict" ]
}

# --- 전제 하나 ------------------------------------------------------------
run_one() {
  local adr="$1" label="$2" body="$3"
  local where="${adr##*/} — ${label}"

  if vetoed "$body"; then
    add_unknown "${where}: 되돌릴 수 없는 명령으로 판정되어 **돌리지 않았다.**"
    return
  fi
  if [ "$SECONDS" -ge "$total_budget" ]; then
    add_unknown "${where}: 예산(${total_budget}초)을 넘겨 돌리지 못했다."
    return
  fi

  local rc=0 out="" outfile errfile reason
  outfile=$(mktemp 2>/dev/null) || outfile=""
  errfile=$(mktemp 2>/dev/null) || errfile=""

  # MUST: 출력을 **파일로 받는다.** 명령 치환을 쓰면 파이프가 닫힐 때까지 기다리는데,
  # `alarm`은 exec된 프로세스만 죽이고 손자는 fd를 쥔 채 남는다 — 상한이 5초라고 보고하면서
  # 실제로는 30초를 기다린 것을 실측했다. 세션 시작을 붙드는 것이 이 훅이 낼 수 있는
  # 가장 나쁜 결과다.
  # MUST: `|| true`를 쓰지 않는다. rc가 0으로 덮이면 계약 3·4를 가를 수 없다.
  # MUST: `</dev/null`을 준다. 없으면 bash -c가 바깥 루프의 stdin을 먹어 **첫 선언만 돌고
  #       나머지가 조용히 사라진다.**
  # perl이 직접 자식을 거두고 **정상 종료**한다. `exec` 뒤 SIGALRM으로 죽게 두면 바깥 셸이
  # `Alarm clock:`을 자기 stderr에 찍고, 그것은 서브셸로 감싸도 막히지 않는다(실측).
  # 게다가 이 형태라야 시간이 다 됐을 때 자식을 실제로 죽인다.
  ( perl -e '
      my $limit = shift;
      my $pid = fork();
      if (!defined $pid) { exit 127 }
      if ($pid == 0) { exec("/bin/bash", "-c", $ARGV[0]); exit 127 }
      $SIG{ALRM} = sub { kill("KILL", $pid); waitpid($pid, 0); exit 142 };
      alarm $limit;
      waitpid($pid, 0);
      alarm 0;
      exit($? >> 8);
    ' "$per_limit" "$body" </dev/null >"${outfile:-/dev/null}" 2>"${errfile:-/dev/null}" ) || rc=$?

  reason=""
  if [ -n "$outfile" ]; then
    out=$(cat "$outfile" 2>/dev/null || true)
    rm -f "$outfile" 2>/dev/null || true
  fi
  if [ -n "$errfile" ]; then
    reason=$(tr '\n' ' ' < "$errfile" 2>/dev/null | cut -c1-200 || true)
    rm -f "$errfile" 2>/dev/null || true
  fi

  # 앞뒤 공백·개행을 걷어낸다. psql -At는 붙이지 않지만 다른 도구는 붙인다.
  out=$(printf '%s' "$out" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' || true)

  if [ "$rc" -ne 0 ]; then
    if [ "$rc" -eq 142 ]; then
      add_unknown "${where}: ${per_limit}초 안에 끝나지 않아 돌리지 못했다."
    else
      add_unknown "${where}: 실행이 실패했다(rc=${rc}). ${reason}"
    fi
  elif [ -z "$out" ]; then
    add_unknown "${where}: **아무것도 재지 못했다** — 출력이 없다. 모집단이 비었거나 쿼리가 대상을 못 찾았다."
  elif [ "${out#OK}" != "$out" ]; then
    n_ok=$((n_ok + 1))
  else
    add_finding "${where}: ${out}"
  fi
}

# --- 파싱 -----------------------------------------------------------------
# CommonMark의 펜스 규칙을 최소한만 따른다 — 여는 펜스의 백틱 개수 이상으로만 닫히고,
# **최상위 펜스만** 인정한다. 템플릿의 예시가 4중 백틱 안에 있어도 실행되지 않는 근거다.
# 들여쓴 펜스는 지원하지 않되 아래에서 그 사실을 보고한다(침묵과 무판정을 섞지 않는다).
#
# BSD awk에는 `{3,}` 구간 반복을 기대하지 않는다. substr로 센다.
# 출력은 한 글자 태그(P/B/E) + 내용이다. 탭·`|`가 라벨에 있어도 깨지지 않는다.
parse_awk='
BEGIN { infence = 0; nb = 0; cap = 0 }
{
  if (substr($0, 1, 3) == "```") {
    n = 0
    while (substr($0, n + 1, 1) == "`") n++
    if (infence == 0) {
      infence = 1; nb = n
      info = substr($0, n + 1)
      sub(/^[ \t]+/, "", info)
      if (substr(info, 1, 7) == "premise") {
        cap = 1
        label = substr(info, 8)
        sub(/^[ \t]+/, "", label)
        sub(/[ \t]+$/, "", label)
        if (label == "") label = "(라벨 없음)"
        print "P" label
      }
      next
    }
    if (n >= nb) {
      infence = 0; nb = 0
      if (cap) { print "E"; cap = 0 }
      next
    }
  }
  if (cap) print "B" $0
}
END { if (cap) print "E" }
'

saw_any=0
indented=""
for adr in docs/adr/ADR-[0-9][0-9][0-9][0-9]-*.md; do
  [ -f "$adr" ] || continue

  # 들여쓴 `premise` 펜스는 파서가 못 본다. 못 봤다는 사실을 알린다.
  if grep -qE '^[[:space:]]+```premise' "$adr" 2>/dev/null; then
    indented="${indented}${adr##*/} "
  fi

  parsed=$(tr -d '\r' < "$adr" | awk "$parse_awk" 2>/dev/null || true)
  [ -z "$parsed" ] && continue
  saw_any=1

  label=""
  body=""
  while IFS= read -r l; do
    case "${l:0:1}" in
      P) label="${l:1}"; body="" ;;
      B) body="${body}${l:1}"$'\n' ;;
      E) [ -n "$label" ] && run_one "$adr" "$label" "$body"; label=""; body="" ;;
    esac
  done <<< "$parsed"
done

if [ -n "$indented" ]; then
  add_unknown "들여쓴 \`premise\` 펜스를 읽지 못했다: ${indented}— 최상위로 옮겨라."
fi

# 선언이 하나도 없으면 조용히 나간다. 채택 초기의 비용이 0이다.
[ "$saw_any" -eq 0 ] && [ -z "$unknowns" ] && exit 0
[ "$n_findings" -eq 0 ] && [ "$n_unknowns" -eq 0 ] && exit 0

# DB가 안 떠 있으면 판정 불가가 무더기로 난다. 원인을 한 줄로 먼저 말한다.
db_note=""
if [ "$n_unknowns" -gt 0 ] && command -v pg_isready >/dev/null 2>&1; then
  pg_isready -q >/dev/null 2>&1 || db_note="PostgreSQL이 응답하지 않는다 — 아래 판정 불가는 대부분 그 때문이다."$'\n'
fi

# 적재가 도는 중이면 접속·질의가 느려져 판정 불가가 난다. 그 원인을 **여기서** 말하는 이유는,
# 원인을 모르면 타임아웃 값을 올리는 쪽으로 손이 가기 때문이다. 실제로 그렇게 한 적이 있고
# 진짜 원인은 꺼야 할 프로세스가 살아 있던 것이었다. 값을 올려도 그 프로세스는 안 죽는다.
#
# 판정 불가가 있을 때만 붙인다 — 이 훅은 ADR 전제 검증기이지 세션 상태 보고자가 아니다.
# "적재가 돌고 있다" 자체를 알리는 것은 `inject-progress.sh`의 몫이다.
if [ "$n_unknowns" -gt 0 ] && [ -r "$root/.claude/hooks/lib/load-process.sh" ]; then
  # shellcheck source=lib/load-process.sh
  . "$root/.claude/hooks/lib/load-process.sh"
  if load_pids=$(stray_load_pids); then
    db_note="${db_note}적재 배치가 돌고 있다(PID ${load_pids}) — 아래 판정 불가는 그 부하 때문일 수 있다. **타임아웃을 올리기 전에 그 프로세스가 아직 돌아야 하는 것인지 먼저 답한다.**"$'\n'
  fi
fi

ctx="ADR이 선언한 전제를 확인했다. 결정이 아직 옳은지에 대한 재료이며, 무엇을 할지는 정하지 않는다.
(참 ${n_ok} · 발견 ${n_findings} · 판정 불가 ${n_unknowns})

${db_note}"
[ -n "$findings" ] && ctx="${ctx}**전제가 깨졌다:**
${findings}
"
[ -n "$unknowns" ] && ctx="${ctx}**판정하지 못했다** — 침묵이 아니라 무판정이다:
${unknowns}"

jq -n --arg ctx "$ctx" \
  '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: $ctx
    }
  }'
exit 0
