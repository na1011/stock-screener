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
# MUST: `per_limit > PGCONNECT_TIMEOUT + statement_timeout`을 유지한다(11 > 4 + 6).
# 부등호가 뒤집히면 psql이 자기 오류 문자열을 내기 전에 alarm이 먼저 프로세스를 죽여,
# "왜 못 쟀는가"가 "5초 안에 끝나지 않았다"로 뭉개진다 — 접속 실패와 질의 지연과 DB
# 다운이 한 문장으로 합쳐진다.
#
# MUST: `total_budget + per_limit < settings.json의 훅 timeout`을 유지한다(75 + 11 < 90).
# 예산은 새 전제를 **시작할 때** 검사하므로 최악은 total_budget을 다 쓴 뒤 마지막 하나가
# per_limit을 다 쓰는 경우다. 훅이 timeout에 걸리면 **출력이 통째로 사라지고**, 이 훅에게
# 침묵은 가장 나쁜 결과다.
#
# 이 부등식은 **아래 run_one이 예산을 심사보다 먼저 볼 때만** 참이다. 순서가 반대였을 때는
# 심사 비용 전체가 부등식 밖에 있었고, 그것이 65개 전제에서 17.8초였다. 시간을 재서 고칠
# 생각이 들면 그 순서부터 본다.
#
# MUST: 여기서 잰 초를 다른 기계·다른 시점의 수와 비교하지 않는다. 이 훅의 비용은 대부분
# 프로세스 fork이고, **fork는 CPU 경합에 3.5배까지 부푼다**(실측). 같은 코드가 유휴와
# 포화에서 3배 넘게 갈리므로, 절대 초는 부하를 함께 적지 않으면 뜻이 없다.
#
# **유휴 벽시계가 total_budget에 가까워지면 뒤쪽 전제가 통째로 예산 컷이 된다** — 그 무판정은
# 「전제가 느리다」가 아니라 「돌리지 못했다」로 나와 원인이 안 보인다. 2026-08-25에 14.11초였고
# 전제 둘의 `indicator_value` 전수 집계를 걷어 **9.53초**가 됐다(ADR-0079 ① · ADR-0071 ②).
# **9.5초도 안전하지 않다** — 위 3.5배가 그대로 걸리면 예산을 넘는다. 다음에 느려지면
# **예산·상한을 올리기 전에 어느 전제가 느린지부터 재고 그 전제를 고친다.**
#
# 2026-09-04에 **처방이 갈리는 경우**를 처음 만났다. 유휴 실측이 **전제 115개 · 합계 23.6초 ·
# 평균 0.20초**이고 가장 느린 것이 ADR-0080의 3.29초였다 — 즉 **한 개가 튄 것이 아니라 수가
# 늘어난 것**이다(103개 22.2초 → 115개 23.6초, 개당 0.12초). 위 「다음 임계는 35개어치」가
# 실제로 온 것이고, 그때는 느린 전제 하나를 고쳐도 예산이 회복되지 않는다.
# **그래서 둘 다 했다** — ADR-0080의 전제를 `regexp_replace` → `left()`로 바꿔 3.29초를
# 2.28초로 줄이고(1초), 예산을 30 → 45초로 올렸다. **예산은 상한이지 소요가 아니므로 유휴
# 세션 시작은 느려지지 않는다** — 경합이 있을 때만 더 기다린다.
#
# 2026-09-13에 **45 → 75초**로 한 번 더 올렸고 이번에는 이유가 다르다. 전제도 개수도 블로트도
# 아니고 **45가 애초에 실측 대역 안에 있었다.** 같은 DB·같은 전제로 조건만 갈아 전수를 재면:
#   조용(warm) 35.2~36.3초 · gradle test 경합 38.7초 · 콜드 캐시 41.2초 · 콜드+6코어 42.6초
# 넷 다 **무판정 0**이다. 즉 45초는 최악(42.6)보다 2.4초 위라 **조건이 조금만 나빠지면 자른다** —
# 그날 세션 시작이 46초·무판정 49였고 그것은 전제의 결함이 아니라 상한의 위치였다.
# **MUST NOT: 이 무판정을 「전제가 느려졌다」로 읽지 않는다.** 먼저 콜드인지 경합인지를 가른다.
# 75는 최악 42.6초의 1.76배다. **질의를 좁혀서는 못 고친다** — 12코어 포화에서는 전제당 7.6배가
# 되어 **고정비(guard fork + psql 접속 4.55초)만 34.6초**라 질의가 0초여도 예산을 채운다.
#
# **MUST NOT: 「전제 모집단을 최근 며칠로 좁히자」를 다시 올리지 않는다**(2026-09-13 실측으로
# 기각). 못 고치는 데다 대가가 크다. `ADR-0126` 펜스를 30일로 좁히면 0.9초를 벌고 **지금 잡고
# 있는 10건 중 8건을 잃는다** — 데이터는 그대로 틀린 채이고 펜스만 입을 다문다. `ADR-0080`은
# 더 나쁘다. 최신 하루로 좁히면 3.65초를 벌지만 **출력이 글자 하나 안 바뀌면서** 검사 행이
# 265만 → 1만(0.4%)이 된다 — 즉 **판정 전문 diff로도 안 잡히는 눈먼 펜스**가 된다.
# 좁히는 것이 옳은 경우는 **그 전제가 묻는 것이 원래 최신 하루일 때뿐**이고, 그때는 좁히는
# 것이 아니라 고치는 것이다.
# 근거는 실측이다: 예산 30초로 도는 SessionStart가 `참 97 · 발견 8 · 판정 불가 10`을 냈는데
# 같은 시점에 예산을 풀어 돌리니 **`참 111 · 발견 4 · 판정 불가 0`**이었다. 즉 **발견 8 중
# 4와 무판정 10 전부가 예산 컷의 산물**이고, 그 거짓 경보를 다음 세션이 진짜 결함으로 읽는다.
#
# 2026-09-01에 그 처방을 한 번 더 밟았다. **28.0초까지 올라 뒤쪽 14개가 상시 무판정**이었고,
# 재보니 **ADR-0094 하나가 5.66초로 전체의 4분의 1**이었다(평범한 CTE 조인이라 플래너가
# 종목 목록을 인덱스로 못 밀어 `indicator_value` 전수를 훑었다). 그 전제만 고쳐 **22.2초**다.
# **전제 수로 나눈 값이 0.22초이므로 여유 7.8초는 대략 35개어치다** — 그것이 다음 임계이고,
# 그 전에 다시 느려지면 **또 특정 전제 하나**를 의심한다. 평균이 오르는 것이 아니라
# 한 개가 튀는 것이 지금까지 두 번 다 그랬다.
#
# 2026-09-04에 **세 번째 원인**이 나왔다 — 전제도 개수도 아니고 **테이블 블로트**다.
# 지표 백필의 UPSERT가 `indicator_value`에 죽은 튜플 **401만(13.5%)**을 남겼고 유휴 합계가
# **33.2초**가 됐다. 전제별로 재보니 **어느 것도 안 튀었다**(최댓값 ADR-0080 2.91초 = 전체의
# 10%, 긴 꼬리). `VACUUM (ANALYZE) indicator_value` 17초로 죽은 튜플 0이 되고 **29.0초**다
# (3회 28.94·29.03·31.02). **느려졌는데 특정 전제가 안 튀면 여기를 본다** —
# `pg_stat_user_tables.n_dead_tup`이 그 답이고, 대량 백필·재적재 뒤가 발생점이다.
#
# **그리고 이 예산은 유휴가 아니라 경합에서 깨진다.** 같은 날 `./gradlew test`(1,356건) 직후
# 훅이 `참 95 · 발견 3 · **판정 불가 19**`를 냈고 ADR-0036이 `statement_timeout`으로 rc=1이었다 —
# 그 전제 단독은 **1.11초**다. 유휴 29초는 예산의 64%뿐이지만 위 3.5배가 걸리면 101초다.
# **MUST: 무거운 gradle 작업 직후에 이 훅의 출력을 회귀로 읽지 않는다** — 그때의 무판정은
# 전제의 결함이 아니라 경합의 산물이고, 판정하려면 유휴에서 다시 돌린다.
per_limit=11
total_budget=75

# --- 값을 붙잡는 갈래 ------------------------------------------------------
# `PREMISE_CAPTURE`에 파일 경로를 주면 전제마다 **판정과 출력 전문**을 그 파일에 적는다.
# 훅은 판정을 개수로만 접으므로(`n_ok`가 OK 문자열을 버린다) **켜기 전/후 대조가 원리적으로
# 불가능하다** — 같은 「OK」가 다른 모집단을 재고 있어도 수는 그대로다. 그 대조가 필요한
# 작업(새 시장을 켜는 것)에서만 쓴다.
#
# MUST: 새 러너를 만들지 않고 여기에 갈래를 둔다. 파서·타임아웃·파괴적 명령 심사·예산이
# 전부 이 파일에 있고, 사본을 만들면 둘이 갈린 채 한쪽만 고쳐진다.
# 붙잡을 때는 예산을 넓힌다 — 이 경로는 SessionStart가 아니라 손으로 부르는 것이라
# settings.json의 훅 timeout이 걸리지 않는다. 위 `total_budget + per_limit < 훅 timeout` 부등식은
# 훅 경로에만 걸린다.
capture="${PREMISE_CAPTURE:-}"
if [ -n "$capture" ]; then
  total_budget=600
  : >"$capture"
fi

# psql이 읽기 전용으로 붙게 한다. 러너가 도메인을 아는 유일한 지점이고, 무엇을 재는지
# 해석하는 것이 아니라 **되돌릴 수 없는 일을 못 하게** 하는 방어다. psql이 아닌 전제에는
# 그냥 무시되는 환경변수다.
# statement_timeout이 3s였을 때 ADR-0094의 전제가 무판정으로 끝난 적이 있다. 그 질의는
# 워밍업된 캐시에서 0.74초인데 **콜드 캐시에서 5.2초**였다 — 적재나 재기동 직후가 그렇다.
# 무판정은 침묵 다음으로 나쁜 결과이므로, 상한을 그 실측 위로 올린다.
#
# MUST NOT: settings.json의 env에 PGOPTIONS가 생겼다는 이유로 이 줄을 지우지 않는다.
# 전역값은 statement_timeout=30s인데 위 per_limit 부등식은 11 > PGCONNECT_TIMEOUT + statement_timeout
# 이다. 이 줄이 사라지면 11 > 4 + 30 이 되어 부등식이 뒤집히고, 전제 전부가 alarm에 잘려
# 무판정이 된다 — 이 훅에게 그것은 침묵 다음으로 나쁜 결과다. 겹쳐 보이지만 값이 다르고,
# default_transaction_read_only도 전역에는 없다.
export PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=6s'
# 2초로는 적재가 도는 동안 접속이 못 붙어 무판정이 났다. 슬롯 고갈이 아니라 CPU 포화다 —
# 적재 JVM과 빌드가 함께 돌면 SCRAM 인증과 프로세스 fork가 실제로 느려진다. 그래서 값을
# 올리는 것이 맞는 처방이고, 위 per_limit 부등식이 그 여유를 감당한다.
#
# **다만 무판정의 원인이 언제나 이것은 아니다.** 끝났어야 할 적재 프로세스가 살아 있어도
# 같은 증상이 나오고, 그때는 값을 아무리 올려도 낫지 않는다 — 실제로 그 상황에서 이 값을
# 올린 적이 있다. 그래서 아래 보고가 "적재가 돌고 있다"를 원인 후보로 함께 낸다.
# MUST: 이 값을 다시 올리기 전에 그 줄이 떴는지부터 본다.
export PGCONNECT_TIMEOUT=4

# 전제마다 만들지 않고 한 번만 만든다 — 위 run_one이 truncate해서 돌려 쓴다.
outfile=$(mktemp 2>/dev/null) || outfile=/dev/null
errfile=$(mktemp 2>/dev/null) || errfile=/dev/null
trap 'rm -f "$outfile" "$errfile" 2>/dev/null || true' EXIT

findings=""
unknowns=""
n_findings=0
n_unknowns=0
n_ok=0

add_finding() { findings="${findings}- ${1}"$'\n'; n_findings=$((n_findings + 1)); }
add_unknown() { unknowns="${unknowns}- ${1}"$'\n'; n_unknowns=$((n_unknowns + 1)); }

# --- 본문 심사 ------------------------------------------------------------
# **이 호출이 세션 시작에서 가장 비싼 반복이다** — 전제 하나에 25.8ms이고 지금 135개라 3.5초다(실측).
# **SessionStart 셋은 병렬이라 벽시계를 max()가 정하는데, 그 max가 이 훅이다** — 유휴 실측
# `inject-progress.sh` 0.04s · `check-doc-staleness.sh` 1.24s · **이 훅 9.53s**(2회 재현).
# 즉 세션 시작 대기는 사실상 이 훅 하나다. **다른 둘을 빠르게 해도 이득이 0이다.**
# **MUST: 전제를 더하거나 걷으면 이 수도 고친다.** 두 번 낡았다 — 처음 적을 때 66이었는데
# 같은 세션이 23분 뒤에 갈아 놓고 안 고쳤고, 84로 고친 뒤 미국을 켠 세션이 셋을 더하며 또
# 안 고쳤다. **세는 법: `grep -h '^```premise' docs/adr/*.md | wc -l`에서 1을 뺀다** —
# `0000-template.md`의 예시 하나는 이 훅이 돌리지 않으므로 grep이 언제나 하나 더 센다.
# 묶어 먹여 한 번에 판정받으면 그 1.9초가 거의 사라진다. **그래도 하지 않기로 했다**
# (2026-08-20 사용자 결정) — 가드가 명령 하나를 받아 판정 하나를 내는 계약을 바꾸면
# 저장소 사본과 `~/.claude/hooks/`의 전역 사본, `lib/regression.sh`의 회귀표가 함께
# 움직인다. **차단 가드는 새는 구멍에 증상이 없다.** 1.9초가 그 위험보다 싸다.
# 여기 적는 이유는 이 수가 코드에 그대로 남아 있어, 안 적으면 다음 세션이 같은 계산을
# 다시 하고 같은 제안을 다시 올리기 때문이다.
#
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

  # MUST: 예산을 **심사보다 먼저** 본다. 반대로 두면 심사 비용이 예산 밖에 놓여, 예산이
  # 이미 바닥나도 남은 전제 전부가 guard-destructive.sh를 한 번씩 더 거친 뒤에야 여기
  # 도달한다 — 그 비용이 65개 합쳐 17.8초였던 적이 있다(선검사로 1.8초가 됐지만, 예산을
  # 우회하는 구조 자체는 그대로였다). 예산이 지키려는 것은 훅 timeout이고, 심사도 그
  # timeout을 쓴다.
  if [ "$SECONDS" -ge "$total_budget" ]; then
    add_unknown "${where}: 예산(${total_budget}초)을 넘겨 돌리지 못했다."
    return
  fi
  if vetoed "$body"; then
    add_unknown "${where}: 되돌릴 수 없는 명령으로 판정되어 **돌리지 않았다.**"
    return
  fi

  local rc=0 out="" reason=""
  : >"$outfile" 2>/dev/null || true
  : >"$errfile" 2>/dev/null || true

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

  [ -s "$outfile" ] && out=$(<"$outfile")
  # stderr는 실패했을 때만 읽는다 — 성공한 전제에서 tr·cut을 돌릴 이유가 없다.
  if [ "$rc" -ne 0 ] && [ -s "$errfile" ]; then
    reason=$(tr '\n' ' ' < "$errfile" | cut -c1-200)
  fi

  # 앞뒤 공백·개행을 걷어낸다. psql -At는 붙이지 않지만 다른 도구는 붙인다.
  # MUST: 내장 치환으로 남긴다. 여기서 sed를 부르면 전제 하나당 프로세스가 하나 더 는다.
  out="${out#"${out%%[![:space:]]*}"}"
  out="${out%"${out##*[![:space:]]}"}"

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

  # 붙잡기는 판정 **뒤**다 — 판정을 안 바꾸고 옆에 적기만 한다.
  if [ -n "$capture" ]; then
    printf '%s\t%s\n' "$where" "${out:-<출력 없음 rc=$rc>}" >>"$capture"
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

# 예산 압박은 **발견이 하나도 없어도** 알린다. 넘기는 순간 뒤쪽 전제가 조용히 무판정이 되는데,
# 그것은 「참」의 수가 줄어드는 것으로만 드러나고 아무도 매 세션 그 수를 세지 않는다. 실제로
# 전제 하나가 예산의 4분의 1을 먹어 뒤쪽 14개가 무판정이던 적이 있다.
#
# 임계를 예산의 4/5에 둔다 — 넘긴 뒤에 알리면 알림이 사후 통보가 된다.
# **예산이 45일 때 이 임계는 36초였고 조용한 실측이 35.2초라 거의 매 세션 울었다** — 알림이
# 상태가 아니라 배경이 되면 그 다음의 진짜 발화도 함께 무시된다. 75에서는 60초이고 실측
# 최악이 42.6초이므로, 이 줄이 뜨면 **정말로 무언가 늘었거나 느려진 것**이다.
budget_note=""
if [ "$SECONDS" -ge "$(( total_budget * 4 / 5 ))" ]; then
  budget_note="전제 예산이 ${SECONDS}초/${total_budget}초다 — 여유가 $(( total_budget - SECONDS ))초뿐이다. **새 전제를 더하기 전에 무엇을 뺄지 함께 정한다.** 넘기면 뒤쪽 전제가 조용히 무판정이 된다. 어느 하나가 튀는지는 전제마다 따로 재서 본다."$'\n'
fi

# 선언이 하나도 없으면 조용히 나간다. 채택 초기의 비용이 0이다.
[ "$saw_any" -eq 0 ] && [ -z "$unknowns" ] && exit 0
[ "$n_findings" -eq 0 ] && [ "$n_unknowns" -eq 0 ] && [ -z "$budget_note" ] && exit 0

# DB가 안 떠 있으면 판정 불가가 무더기로 난다. 원인을 한 줄로 먼저 말한다.
db_note=""
if [ "$n_unknowns" -gt 0 ] && command -v pg_isready >/dev/null 2>&1; then
  pg_isready -d "${DB_NAME:-stock_screener}" -q >/dev/null 2>&1 || db_note="PostgreSQL이 응답하지 않는다 — 아래 판정 불가는 대부분 그 때문이다."$'\n'
fi

# 적재가 도는 중이면 접속·질의가 느려져 판정 불가가 난다. 그 원인을 **여기서** 말하는 이유는,
# 원인을 모르면 타임아웃 값을 올리는 쪽으로 손이 가기 때문이다. 실제로 그렇게 한 적이 있고
# 진짜 원인은 꺼야 할 프로세스가 살아 있던 것이었다. 값을 올려도 그 프로세스는 안 죽는다.
#
# 판정 불가가 있을 때만 붙인다 — 이 훅은 ADR 전제 검증기이지 세션 상태 보고자가 아니다.
# "적재가 돌고 있다" 자체를 알리는 것은 `inject-progress.sh`의 몫이다.
#
# MUST: `stray_load_pids`의 rc=2(판정 불가)를 "없다"로 읽지 않는다. lib이 그 계약을 명시하고
# 있고, 여기서 삼키면 **"적재가 안 돈다"와 "적재가 도는지 모른다"가 같은 침묵이 된다** —
# 바로 아래 주석이 "그 줄이 떴는지부터 본다"고 지시하는데 그 줄의 부재가 두 가지를 뜻하게 된다.
# 침묵이 절대 건강을 뜻하지 않게 하는 것이 이 훅의 존재 이유다.
if [ "$n_unknowns" -gt 0 ] && [ -r "$root/.claude/hooks/lib/load-process.sh" ]; then
  # shellcheck source=lib/load-process.sh
  . "$root/.claude/hooks/lib/load-process.sh"
  set +e
  load_pids=$(stray_load_pids)
  load_rc=$?
  set -e
  if [ "$load_rc" -eq 0 ]; then
    db_note="${db_note}적재 배치가 돌고 있다(PID ${load_pids}) — 아래 판정 불가는 그 부하 때문일 수 있다. **타임아웃을 올리기 전에 그 프로세스가 아직 돌아야 하는 것인지 먼저 답한다.**"$'\n'
  elif [ "$load_rc" -eq 2 ]; then
    db_note="${db_note}적재가 도는 중인지는 판정하지 못했다(pgrep 또는 ps가 없다) — 아래 판정 불가의 원인 후보에서 그것을 지우지 마라."$'\n'
  fi
fi

ctx="ADR이 선언한 전제를 확인했다. 결정이 아직 옳은지에 대한 재료이며, 무엇을 할지는 정하지 않는다.
(참 ${n_ok} · 발견 ${n_findings} · 판정 불가 ${n_unknowns})

${budget_note}${db_note}"
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
