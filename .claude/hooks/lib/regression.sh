#!/bin/bash
# Bash 훅 회귀표. `bash .claude/hooks/lib/regression.sh` 로 한 번에 돌린다.
#
# 왜 스크립트로 남기는가 — 이 훅들은 **오탐과 침묵이 정반대 방향으로 나쁘고**, 한쪽만 보면
# 반대쪽이 무너진 것을 모른다. 다음에 이 훅을 만지는 세션이 같은 표를 한 번에 돌릴 수 있어야
# 한다.
#
# 아래 위험 문자열은 **리터럴로 적는다.** 훅이 보는 것은 이 파일의 내용이 아니다:
#   - guard-destructive.sh 는 `tool_input.command` 만 읽는다. 이 표를 돌리는 명령은
#     `bash .claude/hooks/lib/regression.sh` 한 줄이고 **파일 내용은 거기 나타나지 않는다.**
#   - guard-secrets.sh 는 **경로만** 본다. 파일 내용을 읽는 코드가 없다.
#
# 처음에는 `R=$(printf 'r%s' 'm')` 식으로 조립해 놓고 "리터럴로 적으면 막힌다"고 적어 뒀는데
# **그것이 사실이 아니었다.** 실제로 막힌 것은 문자열이 명령에 그대로 있던 **인라인 임시
# 테스트**였고, 나는 그 경험을 파일에 대한 주장으로 잘못 옮겼다. 전역 CLAUDE.md의
# "MUST NOT: 차단을 예상해 미리 문자열을 쪼개거나 파일로 우회하지 않는다 — 예방적 우회는
# 명령을 읽기 어렵게 만들 뿐이다"가 정확히 이 경우를 가리킨다. 되돌린 기록을 남기는 이유는,
# 없으면 다음 세션이 같은 우회를 다시 만들기 때문이다.
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}" || exit 1
HOOKS=.claude/hooks

command -v jq >/dev/null 2>&1 || { echo "jq가 없어 회귀표를 돌릴 수 없다."; exit 1; }

pass=0; fail=0; skipped=0

# $1 기대값, $2 설명, $3 명령, $4 훅
#
# MUST: 훅이 **아무것도 출력하지 않는 것**이 정상 통과다. 그 빈 출력을 jq에 넣으면 jq도 빈
# 문자열을 내므로, 기본값을 jq의 `//` 연산자에 맡기면 "조용히 통과"와 "판정 실패"가 같아진다.
# 그래서 빈 출력을 셸에서 먼저 가른다 — 처음에 이걸 틀려 정상 통과 8건이 FAIL로 나왔다.
check() {
  local want="$1" desc="$2" cmd="$3" hook="$4" got out
  case "$hook" in
    guard)
      out=$(jq -n --arg c "$cmd" '{tool_input:{command:$c}}' | "$HOOKS/guard-destructive.sh" 2>/dev/null)
      if [ -z "$out" ]; then got="pass"
      else got=$(printf '%s' "$out" | jq -r '.hookSpecificOutput.permissionDecision // "pass"'); fi ;;
    bootstrap)
      out=$(jq -n --arg c "$cmd" '{tool_input:{command:$c}}' | "$HOOKS/warn-bootstrap-args.sh" 2>/dev/null)
      if [ -z "$out" ]; then got="침묵"; else got="발화"; fi ;;
    rule)
      out=$(jq -n --arg c "$cmd" --arg s "sess-$RANDOM$RANDOM" '{session_id:$s,tool_input:{command:$c}}' \
            | "$HOOKS/remind-rule-on-shell.sh" 2>/dev/null)
      if [ -z "$out" ]; then got="침묵"; else got="발화"; fi ;;
    # 이 분기만 `command`가 아니라 `file_path`를 먹인다 — Edit·Write 경로의 훅이라 입력
    # 모양이 다르다. `$cmd` 자리에 경로를 넘긴다.
    write)
      out=$(jq -n --arg p "$cmd" --arg s "sess-$RANDOM$RANDOM" '{session_id:$s,tool_input:{file_path:$p}}' \
            | "$HOOKS/remind-rule-on-write.sh" 2>/dev/null)
      if [ -z "$out" ]; then got="침묵"; else got="발화"; fi ;;
    load)
      out=$(jq -n --arg c "$cmd" --arg s "sess-$RANDOM$RANDOM" '{session_id:$s,tool_input:{command:$c}}' \
            | "$HOOKS/warn-load-running.sh" 2>/dev/null)
      if [ -z "$out" ]; then got="침묵"; else got="발화"; fi ;;
    # `$cmd`를 `도구:명령`으로 받는다 — 이 훅만 tool_name으로도 갈리므로 명령만으로는 못 잰다.
    triage)
      out=$(jq -n --arg t "${cmd%%:*}" --arg c "${cmd#*:}" '{tool_name:$t,tool_input:{command:$c}}' \
            | "$HOOKS/remind-finding-triage.sh" 2>/dev/null)
      if [ -z "$out" ]; then got="침묵"; else got="발화"; fi ;;
    # 이 둘만 `HOME`을 격리한다. 판정이 `$DUMP_DIR`의 내용에 달려 있어 기계의 상태를 그대로
    # 쓰면 **덤프가 있는 기계에서는 deny 경로가 영영 안 돌고**, 그 침묵이 통과로 읽힌다.
    rebuild-nodump)
      home=$(mktemp -d)
      out=$(jq -n --arg c "$cmd" '{tool_input:{command:$c}}' \
            | HOME="$home" "$HOOKS/guard-rebuild-without-dump.sh" 2>/dev/null)
      if [ -z "$out" ]; then got="pass"
      else got=$(printf '%s' "$out" | jq -r '.hookSpecificOutput.permissionDecision // "pass"'); fi ;;
    rebuild-dump)
      home=$(mktemp -d)
      mkdir -p "$home/Library/Application Support/stock-screener/dumps"
      : > "$home/Library/Application Support/stock-screener/dumps/fixture.dump"
      out=$(jq -n --arg c "$cmd" '{tool_input:{command:$c}}' \
            | HOME="$home" "$HOOKS/guard-rebuild-without-dump.sh" 2>/dev/null)
      if [ -z "$out" ]; then got="pass"
      else got=$(printf '%s' "$out" | jq -r '.hookSpecificOutput.permissionDecision // "pass"'); fi ;;
  esac
  [ -z "$got" ] && got="(빈응답)"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

# 알려진 구멍. **기대값에는 옳은 값을 적는다** — 현행 동작을 적으면 이 표가 구멍을 인증하고,
# 그러면 "판정 불변"을 요구하는 다음 변경이 그 구멍을 **지키는 펜스**가 된다.
#
# 그래서 세 번째 상태를 둔다. 어긋나면 KNOWN(실패 아님), **맞으면 FAIL**이다 —
# 구멍이 막혔는데 표가 조용하면 그 사실을 아무도 모르고, 이 줄은 영영 KNOWN으로 남는다.
holes=0
check_hole() {
  local want="$1" desc="$2" cmd="$3" hook="$4" got out
  out=$(jq -n --arg c "$cmd" '{tool_input:{command:$c}}' | "$HOOKS/$hook" 2>/dev/null)
  if [ -z "$out" ]; then got="pass"
  else got=$(printf '%s' "$out" | jq -r '.hookSpecificOutput.permissionDecision // "pass"'); fi
  if [ "$got" = "$want" ]; then
    fail=$((fail+1))
    printf '  \033[31mFAIL\033[0m %-8s %s  (구멍이 막혔다 — check 로 승격하라)\n' "$got" "$desc"
  else
    holes=$((holes+1))
    printf '  \033[33mKNOWN\033[0m %-7s %s  (알려진 구멍, 기대: %s)\n' "$got" "$desc" "$want"
  fi
}

hd() { printf '%s\n' "$@"; }

echo "── 실제 파괴 명령은 막혀야 한다 ──"
check deny "실제 파괴 삭제"                    "rm -rf build" guard
check deny "실제 force push"                   "git push --force" guard
check pass "force-with-lease 는 허용"          "git push --force-with-lease" guard
check deny "psql -c 파괴 SQL"                  'psql -d x -c "drop table y"' guard

echo "── 실행되는 heredoc 본문은 남아야 한다 (무조건 제거하면 깨진다) ──"
check deny "bash <<EOF 본문에 파괴 삭제"        "$(hd 'bash <<EOF' 'rm -rf /tmp/x' 'EOF')" guard
check deny "sh -s <<EOF 본문에 파괴 삭제"       "$(hd 'sh -s <<EOF' 'rm -rf /tmp/x' 'EOF')" guard
check deny "heredoc 종료 뒤의 실제 파괴 삭제"   "$(hd 'git commit -F - <<MSG' 'msg' 'MSG' 'rm -rf /tmp/x')" guard
check deny "psql <<SQL 본문에 파괴 SQL"         "$(hd 'psql -d x <<SQL' 'drop table y;' 'SQL')" guard
check deny "psql <<-SQL (탭 허용) 본문"         "$(hd 'psql -d x <<-SQL' '	drop table y;' '	SQL')" guard

echo "── 문서화된 명령은 막지 않아야 한다 ──"
check pass "heredoc 본문에 파괴 삭제 문서화"    "$(hd 'git commit -F - <<MSG' 'fix: 정리' '' 'rm -rf build/ 를 돌려야 복제본이 사라진다' 'MSG')" guard
check pass "heredoc 본문에 force push 문서화"   "$(hd 'git commit -F - <<MSG' 'docs: 금지' '' 'git push --force 는 금지다' 'MSG')" guard
check pass "여러 줄 -m 메시지에 파괴 명령"      "$(hd "git commit -m '정리:" "rm -rf build/ 설명'")" guard
check pass "heredoc 본문에 파괴 SQL 문서화"     "$(hd 'git commit -F - <<MSG' 'docs: SQL' '' 'drop table 은 금지다' 'MSG')" guard

echo "── 시크릿 읽기는 확인을 요구해야 한다 ──"
check ask  "cat .env"                          "cat .env" guard
check ask  "cp .env 복사"                      "cp .env /tmp/x" guard
check ask  "base64 ssh 키"                     "base64 ~/.ssh/id_rsa" guard
check ask  "heredoc 뒤에 숨긴 cat .env"        "$(hd 'git commit -F - <<MSG' 'msg' 'MSG' 'cat .env')" guard
check ask  "상대경로 secrets/"                 "grep KEY secrets/prod.yaml" guard

# 아래 둘은 **선검사를 어디에 거는가**를 잰다. 세그먼트 분해가 전체 문자열에 없던 줄머리를
# 만들기 때문에, `$cmd`나 `$clean`을 선검사 대상으로 고르면 여기서 ask 가 pass 로 뒤집힌다.
# `secret_paths` 의 `(^|[[:space:]/])secrets?/` 가 `^` 앵커를 쓰는 것이 그 이유다.
check ask  "() 안의 secrets/ (줄머리가 생긴다)" 'KEY=$(secrets/cat api.key)' guard
check ask  "&& 뒤에 붙은 secrets/"             "make deploy&&secrets/cat prod.key" guard
check ask  "인용된 경로"                       'cat ".env"' guard

echo "── 시크릿과 무관한 명령은 조용해야 한다 ──"
check pass "예시 파일 조회"                    "grep -n SENTRY .env.example" guard
check pass "push | tail (+메시지에 .env 언급)" "$(hd 'git add x' 'git commit -F - <<MSG' '.env가 test에 없으므로 평가되지 않는다' 'MSG' 'git push 2>&1 | tail -3')" guard
check pass "checkEnv | tail"                   "./gradlew checkEnv 2>&1 | tail -5" guard

echo "── deny 가 ask 를 이긴다 (§1b·§2 를 한 루프로 합치면 뒤집힌다) ──"
# 모든 deny 소스(§1b 루프 + heredoc 판정)가 어떤 ask 보다 **먼저** 평가되는 것이 현재 구조다.
# 합치면 세그먼트를 만난 자리에서 ask 를 emit 하게 되어 첫 줄이 ask 로 뒤집힌다.
check deny "ask 세그먼트가 앞에 있어도 deny"   "cat .env && git push --force" guard
check deny "deny 세그먼트가 앞에 있으면 deny"  "git push --force && cat .env" guard

echo "── argv0 판정의 경계 ──"
check deny "절대경로 argv0"                    "/usr/bin/git push --force" guard
# 의도된 결손이다(파일 머리 주석: 값을 받는 플래그를 쓰는 래퍼는 argv0 을 놓친다).
# **pass 로 못박는 이유** — 안 적으면 다음 세션이 이것을 버그로 보고 정규식 군비경쟁을 재개한다.
check pass "sudo -u 래퍼는 놓친다(의도된 결손)" 'sudo -u postgres psql -d x -c "drop table y"' guard

echo "── 알려진 구멍 ──"
# `'"'"'\'"'"''` 는 작은따옴표 안에 아포스트로피를 넣는 표준 POSIX 형식이다. `strip_quotes_global`
# (lib/bash-text.sh)의 상태기계가 그 마지막 따옴표에서 sq=1 로 열린 채 **문자열 끝까지 삼켜**,
# 뒤에 오는 실제 명령이 §1 의 파괴 판정에서 보이지 않는다.
#
# 고치려면 그 상태기계를 손봐야 하고 그것은 별개 변경이다. 여기서는 **옳은 기대값을 적어 둔다** —
# 구멍이 막히는 날 이 줄이 FAIL 로 울어 승격을 요구한다.
check_hole deny "'\''  뒤의 파괴 삭제"         "git commit -m 'don'\''t' && rm -rf build" guard-destructive.sh
check_hole deny "'\''  뒤의 force push"        "git commit -m 'it'\''s fine' && git push --force" guard-destructive.sh

echo "── 이 저장소가 처방하는 종료·복원은 막지 않는다 ──"
# 아래 넷은 **커밋 4(pkill·dropdb·pg_restore 판정 추가)가 깨뜨리지 말아야 할 것**이다.
# 지금도 pass 이고 그 뒤로도 pass 여야 한다 — 그것을 지키는 것이 이 네 줄의 존재 이유다.
check pass "kill <PID> (ops/stop.sh 가 처방)"  "kill 12345" guard
check pass "kill -9 <PID>"                     "kill -9 12345" guard
check pass "pg_restore -l (ADR-0052 전제)"     "pg_restore -l /tmp/x.dump" guard
check pass "pg_dump 는 읽기다"                 "pg_dump -Fc stock_screener -f /tmp/x.dump" guard
check pass "pg_restore -f 는 파일로만 쓴다"    "pg_restore -f /tmp/out.sql /tmp/x.dump" guard
# ADR-0052 전제의 실제 형태. 절대경로 + 파이프라 argv0 추출과 세그먼트 분해를 둘 다 지난다.
check pass "전제가 쓰는 pg_restore -l 파이프"  "n=\$(/opt/homebrew/bin/pg_restore -l /tmp/x.dump | grep -c 'TABLE DATA')" guard

echo "── 되돌리기 어려운 운영 명령은 물어야 한다 ──"
check ask  "pkill -f 는 운영 앱도 죽인다"      "pkill -f java" guard
check ask  "killall"                           "killall java" guard
check deny "dropdb"                            "dropdb stock_screener" guard
check ask  "pg_restore -d (운영 DB에 쓴다)"    "pg_restore -d stock_screener /tmp/x.dump" guard
check ask  "pg_restore --clean"                "pg_restore --clean --if-exists -d stock_screener /tmp/x.dump" guard
check ask  "pg_restore -C"                     "pg_restore -C -d postgres /tmp/x.dump" guard
# ops/dump-db.sh 가 복구 처방으로 출력하는 바로 그 줄. deny 가 아니라 ask 여야 한다 —
# 되돌려야 하는 순간에 되돌리는 길을 막으면 안 된다.
check ask  "dump-db.sh 가 처방하는 복구 명령"  "pg_restore -d stock_screener -c --if-exists -j4 /tmp/x.dump" guard
# `short_flags_of` 로 단문자를 찾으면 `-Fc` 가 `-c` 로 오탐된다. 토큰 완전 일치라야 통과한다.
check pass "-Fc 는 --clean 이 아니다"          "pg_restore -Fc -l /tmp/x.dump" guard

echo "── deny 는 새 ask 보다도 먼저다 ──"
check deny "pkill 과 파괴 삭제가 같이 있으면"  "pkill -f java && rm -rf build" guard
check deny "pg_restore -d 와 파괴 SQL"         'pg_restore -d s /tmp/x.dump && psql -d x -c "drop table y"' guard

echo "── 적재 인자 알림 ──"
check 침묵 "적재 명령을 문서화한 커밋 메시지"  "$(hd 'git commit -F - <<MSG' 'docs: 절차' '' "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load'" 'MSG')" bootstrap
check 발화 "실제 적재 실행(플래그 없음)"        "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load'" bootstrap
check 침묵 "실제 적재 실행(플래그 있음)"        "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load --reload-existing=true'" bootstrap

echo "── 덤프 없는 수정계수 재구축 (ADR-0049) ──"
#
# 막는 범위가 **좁은 것이 이 가드의 설계**다. 덤프가 하나라도 있으면 다시 걸리지 않는다 —
# 신선도 창을 걸면 정상 재구축과 되돌리기 리허설까지 막혀 "선택지를 줄이는 기제"가 된다.
# 아래 두 짝이 그 좁음을 실제로 잰다: 같은 명령이 덤프 유무로만 갈려야 한다.
check deny "덤프 없이 재구축 실행"              "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=adjustment-rebuild'" rebuild-nodump
check pass "덤프가 있으면 같은 명령이 통과"      "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=adjustment-rebuild'" rebuild-dump
check pass "재구축을 문서화한 커밋 메시지"        "$(hd 'git commit -F - <<MSG' 'docs: 절차' '' "./gradlew bootRun --args='--job=adjustment-rebuild'" 'MSG')" rebuild-nodump
check pass "gradle이 아닌 명령의 단순 언급"       "echo --job=adjustment-rebuild" rebuild-nodump
# `backfill`·`financial-refill`도 지운다 — 가드가 셋을 덮는다. 여기에 「다른 잡」으로
# 두었다가 가드가 넓어진 뒤에도 안 고쳐져 표가 실패를 상시로 냈다(2026-09-10~09-11).
check deny "덤프 없이 백필 실행"                "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill'" rebuild-nodump
check deny "덤프 없이 재무 리필 실행"            "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=financial-refill'" rebuild-nodump
check pass "덮지 않는 잡은 건드리지 않는다"       "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load'" rebuild-nodump

echo "── 적재 중 gradle 알림 ──"
#
# 이 그룹만 신호가 **살아 있는 프로세스**다. 명령 문자열만으로는 재현되지 않으므로 진짜
# JVM을 띄운다. 판정이 틀리는 방향이 둘이고 둘 다 위험하다 — 못 잡으면 적재가 조용히
# 처음부터 다시 가고, 잘못 잡으면 첫 발화가 거짓이라 그 뒤의 참 발화도 무시된다.
. "$HOOKS/lib/load-process.sh"

if stray_load_pids >/dev/null 2>&1; then
  # MUST: 건너뛴 사실을 출력한다. 건너뛴 것이 통과로 읽히면 이 표가 잡으려는 병에 이 표가 걸린다.
  skipped=$((skipped+1))
  echo "  건너뜀 — 진짜 적재가 도는 중이라 '적재 없음' 기대가 성립하지 않는다."
else
  check 침묵 "적재가 없으면 build 는 조용하다"    "./gradlew build" load
  check 침묵 "적재가 없으면 clean 도 조용하다"    "./gradlew clean" load
fi

# 적재 기동 알림은 살아 있는 프로세스와 무관하다 — 명령의 모양만 본다.
check 발화 "적재를 띄우면 창이 열렸다고 알린다"  "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill'" load
check 침묵 "gradle 이 아닌 명령"                 "psql -d stock_screener -c 'select 1'" load
# 기동 판정을 **명령 전문**에서 하면 이 줄이 "적재를 띄웠다"로 나온다. 실제로 그랬다.
# 적재가 없을 때는 침묵이 정답이고, 기동으로 오독하는지는 아래 fake JVM 그룹에서 메시지로 가른다.
check 침묵 "커밋 메시지의 --job= 은 기동이 아니다" \
  './gradlew build && git commit -m "chore: bootRun --job=universe 문서화"' load
#
# 프로세스 나이로 "방금 띄운 것"과 "이미 돌던 것"을 가른다(경계는 warn-load-running.sh의 90초).
# 그 판정이 없으면 적재를 띄울 때마다 중복 경고가 뜬다. 나이 자체는 여기서 재고, 90초 경계를
# 넘는 중복 기동 시나리오는 표에 넣지 않는다 — 픽스처 하나가 95초를 먹기 때문이다.
# **그 경로는 표가 보증하지 않는다.**
#
# MUST: 임계가 아니라 **독립 출처와 대조한다.** 전에는 `> 3600`이었는데 PID 1은 launchd이고
# **그 나이는 곧 부팅 이후 시간**이라 부팅 후 1시간 동안 매 실행이 거짓 FAIL이었다(실측:
# 부팅 23분째에 FAIL, 72분째에 같은 코드가 OK). 거짓 FAIL은 이 표에서 가장 나쁜 고장이다 —
# 같은 파일이 위에서 적듯 "잘못 잡으면 첫 발화가 거짓이라 그 뒤의 참 발화도 무시된다".
# 대조값은 `ps -o lstart`(시작 시각 문자열)를 `date`로 푼 나이다 — etime과 **다른 형식을 다른
# 파서로** 읽으므로 둘이 맞으면 etime 파싱이 맞는 것이고, 부팅 직후든 한 달 뒤든 성립한다.
# MUST NOT: `kern.boottime`과 대조하지 않는다 — 시계 보정 때 그 값만 밀려 차이가 1~3초로 흔들렸다
# (실측: launchd의 lstart 18:03:38이 boottime 18:03:39.28보다 앞섰다 — 보정 없이는 불가능한 순서).
# 허용 오차 2초는 두 번 읽는 사이의 초 넘어감이다.
_lstart=$(LC_ALL=C ps -o lstart= -p 1 2>/dev/null | sed 's/ *$//')
_started=$(LC_ALL=C date -j -f '%a %b %e %T %Y' "$_lstart" +%s 2>/dev/null)
_expected=$(( $(date +%s) - ${_started:-0} ))
_actual=$(. "$HOOKS/lib/load-process.sh"; load_process_age_seconds 1 2>/dev/null || echo -1)
_diff=$(( _actual - _expected )); [ "$_diff" -lt 0 ] && _diff=$(( -_diff ))
if [ -n "$_started" ] && [ "$_actual" -ge 0 ] && [ "$_diff" -le 2 ]; then
  pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "확정" "오래된 PID의 나이를 잰다"
else
  fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s (etime=%s · lstart=%s)\n' "실패" "오래된 PID의 나이를 잰다" "$_actual" "$_expected"
fi

# 진짜 JVM을 띄워 분기 B를 확인한다. `comm`이 java여야 하므로 셸 프로세스로는 대신할 수 없다.
if command -v java >/dev/null 2>&1; then
  fake_dir=$(mktemp -d)
  printf 'public class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(20000); } }\n' > "$fake_dir/Sleeper.java"
  java "$fake_dir/Sleeper.java" --job=backfill >/dev/null 2>&1 &
  fake_pid=$!
  # 단일 파일 실행은 메모리 안에서 컴파일하므로 뜨는 데 시간이 걸린다. 보일 때까지 기다린다.
  fake_seen=false
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if stray_load_pids 2>/dev/null | grep -qw "${fake_pid}"; then fake_seen=true; break; fi
    sleep 1
  done
  if [ "$fake_seen" = true ]; then
    check 발화 "적재 중 build 는 알린다"           "./gradlew build" load
    check 발화 "적재 중 clean 은 알린다"           "./gradlew clean" load
    check 침묵 "적재 중에도 checkDuplicateFiles"   "./gradlew checkDuplicateFiles" load
    # 리다이렉션이 태스크 이름으로 읽히면 예외가 무너진다. 실제로 그렇게 오발화했다.
    check 침묵 "예외에 리다이렉션이 붙어도"        "./gradlew checkDuplicateFiles -q 2>&1 | tail -3" load
    check 발화 "예외와 컴파일 태스크를 함께 부르면" "./gradlew checkDuplicateFiles build" load
    check 침묵 "적재 중 커밋 메시지 속 gradle"     "$(hd 'git commit -F - <<MSG' 'docs: 절차' '' './gradlew build 를 먼저 돌린다' 'MSG')" load
    check 침묵 "적재 중 인용만 한 gradle"          'echo "./gradlew build"' load
    check 침묵 "적재 중 grep 이 이름만 부름"        'grep -rn "gradlew build" docs/' load
    #
    # 한 겹 감싼 형태. **전부 거짓 침묵이었다** — 세그먼트의 첫 토큰만 보던 시절에는
    # 인터프리터·xargs가 인용 안쪽을 가렸고, 래퍼 벗기기 루프는 옵션 토큰에서 멈췄다
    # (`caffeinate`는 목록에 있었는데도 `-s` 때문에 못 잡았다).
    check 발화 "sh -c 로 감싼 gradle"               "sh -c './gradlew build'" load
    check 발화 "bash -lc 로 감싼 gradle"            'bash -lc "./gradlew build"' load
    check 발화 "xargs 로 넘긴 gradle"               "xargs -I{} ./gradlew {}" load
    check 발화 "옵션 붙은 caffeinate"               "caffeinate -s ./gradlew build" load
    check 발화 "옵션 붙은 time"                     "time -p ./gradlew build" load
    check 발화 "옵션 붙은 env"                      "env -i ./gradlew build" load
    check 발화 "값을 받는 옵션이 붙은 sudo"          "sudo -u x ./gradlew build" load
    #
    # 발화/침묵만으로는 **분기 A가 분기 B를 가로챈 것**을 못 가른다. 메시지를 본다.
    hijack=$(jq -n --arg c './gradlew build && git commit -m "chore: bootRun --job=universe 문서화"' \
                   --arg s "sess-$RANDOM$RANDOM" '{session_id:$s,tool_input:{command:$c}}' \
             | "$HOOKS/warn-load-running.sh" 2>/dev/null \
             | jq -r '.hookSpecificOutput.additionalContext // ""')
    case "$hijack" in
      *"적재 배치가 돌고 있다"*)
        pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "경고" "커밋 메시지를 기동으로 오독하지 않는다" ;;
      *)
        fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s\n' "오독" "커밋 메시지를 기동으로 오독하지 않는다" ;;
    esac
  else
    skipped=$((skipped+1))
    echo "  건너뜀 — 가짜 적재 JVM이 뜨지 않아 분기 B를 재현하지 못했다."
  fi
  kill "$fake_pid" 2>/dev/null || true
  wait "$fake_pid" 2>/dev/null || true
  rm -rf "$fake_dir"
else
  skipped=$((skipped+1))
  echo "  건너뜀 — java가 없어 가짜 적재 JVM을 띄우지 못했다."
fi

# **비java 프로세스를 적재로 읽지 않는가.** Bash 도구의 셸은 명령 전문을 자기 argv에 싣기
# 때문에, 이 필터가 없으면 패턴을 언급하기만 한 도구 호출이 전부 적재로 잡힌다.
bash -c 'sleep 6; :' pad --job=backfill >/dev/null 2>&1 &
nonjava_pid=$!
sleep 1
if stray_load_pids 2>/dev/null | grep -qw "${nonjava_pid}"; then
  fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s\n' "발화" "비java 프로세스를 적재로 읽지 않는다"
else
  pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "침묵" "비java 프로세스를 적재로 읽지 않는다"
fi
kill "$nonjava_pid" 2>/dev/null || true
wait "$nonjava_pid" 2>/dev/null || true

echo "── 규칙 포인터 알림 ──"
check 발화 "셸로 build.gradle 읽기"            "grep -n bootRun build.gradle" rule
check 침묵 "경로 없는 명령"                    "./gradlew build" rule
check 침묵 "git add 로 이름만 부름"            "git add docs/progress.md" rule
check 침묵 "커밋 메시지가 경로를 언급"          "$(hd 'git commit -F - <<MSG' 'fix: x' '' 'CLAUDE.md 금지 절에 적었다' 'MSG')" rule
check 발화 "heredoc 뒤의 실제 셸 읽기"          "$(hd 'git commit -F - <<MSG' 'msg' 'MSG' 'grep -n x build.gradle')" rule
#
# 아래는 **셸로 하네스를 고치는** 경로다. 전부 `paths` 규칙이 원리적으로 못 잡는 형태이고,
# 하나라도 침묵으로 돌아가면 그 경로는 규칙 없이 편집된다.
check 발화 "./ 접두어로 하네스 편집"            "sed -i '' 's/a/b/' ./.claude/hooks/guard-secrets.sh" rule
check 발화 "git checkout 으로 되돌리기"          "git checkout HEAD~1 -- .claude/hooks/guard-secrets.sh" rule
check 발화 "git apply 로 패치"                  "git apply /tmp/p.patch .claude/rules/harness-core.md" rule
check 발화 "전역 사본 편집"                     "sed -i '' 's/a/b/' ~/.claude/hooks/guard-secrets.sh" rule
check 발화 "find -exec 로 트리 일괄 편집"        "find .claude/hooks -name '*.sh' -exec sed -i '' 's/a/b/' {} +" rule
check 발화 "인터프리터 heredoc 이 파일을 씀"     "$(hd 'python3 - <<PY' "open('.claude/hooks/x.sh','w').write('x')" 'PY')" rule
check 침묵 "git diff 로 조회"                   "git diff .claude/rules/harness-core.md" rule

echo "── 읽지 않고 편집한 경로 (Write 구멍) ──"
#
# `paths` 규칙은 파일을 Read할 때 뜬다. 새 파일 Write는 사전 Read를 요구하지 않으므로
# 그 경로로 만들면 규칙이 원리적으로 오지 않는다. 침묵으로 돌아가면 그 구멍이 다시 열린다.
check 발화 "새 훅을 Write 로 만듦"               ".claude/hooks/new-hook.sh" write
check 발화 "새 규칙 파일을 Write 로 만듦"         ".claude/rules/new-rule.md" write
check 발화 "CLAUDE.md 편집"                     "CLAUDE.md" write
check 발화 "전역 가드 사본 편집"                  "$HOME/.claude/hooks/guard-secrets.sh" write
# 규칙 글롭 어디에도 안 걸리는 경로여야 이 케이스가 무언가를 잰다. 루트 마크다운 중
# `CLAUDE.md`·`README.md`만 규칙이 있으므로 `TODO.md`가 그 자리다.
# **`docs/` 아래를 쓰지 않는다** — `doc-facts.md`가 `docs/**/*.md`를 덮어 전부 발화한다.
check 침묵 "규칙이 없는 경로"                     "TODO.md" write

echo "── 거울 드리프트 (check-doc-staleness §5) ──"
#
# 저장소를 건드리지 않는다. 거울 파일만 임시 트리에 복사해 거기서 비튼다 — 손으로 비틀고
# 되돌리는 검증은 되돌리기를 잊고, 그러면 다음 세션이 갈라진 사본을 정본으로 믿는다.
# $1 기대(발화/침묵), $2 설명, $3 비틀 파일(없으면 원본 그대로), $4 perl 치환식
check_mirror() {
  local want="$1" desc="$2" target="${3:-}" expr="${4:-}" tmp f got
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  for f in .claude/rules/data-pipeline.md .claude/rules/test.md .claude/skills/batch-load/SKILL.md \
           .claude/hooks/warn-load-running.sh; do
    mkdir -p "$tmp/$(dirname "$f")" && cp "$f" "$tmp/$f"
  done
  [ -n "$target" ] && perl -i -pe "$expr" "$tmp/$target"
  if CLAUDE_PROJECT_DIR="$tmp" "$HOOKS/check-doc-staleness.sh" </dev/null 2>/dev/null \
     | jq -r '.hookSpecificOutput.additionalContext // empty' | grep -q '거울\|사본이 한쪽'; then
    got="발화"
  else
    got="침묵"
  fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

check_mirror 침묵 "사본이 서로 같으면 조용하다"
check_mirror 발화 "공공데이터포털 사본이 갈리면 잡는다" \
  ".claude/rules/data-pipeline.md" 's/응답이 동일하다/응답이 모두 같다/'
check_mirror 발화 "ADJ_CLOSE 사본이 갈리면 잡는다" \
  ".claude/rules/test.md" 's/봉 하나로 값이 나오는 첫 지표다/봉 하나로 값이 나오는 최초 지표다/'
check_mirror 발화 "사본이 한쪽에서 사라지면 잡는다" \
  ".claude/skills/batch-load/SKILL.md" 's/공공데이터포털은 없는 종목/공공데이터포털은 어떤 종목/'

#
# 최소 git 트리를 만들어 거기서 돌린다. 저장소 자체를 비틀면 되돌리기를 잊고, 그러면 다음
# 세션이 깨진 참조를 정상으로 믿는다(거울 픽스처와 같은 이유).
# $1 기대(발화/침묵), $2 설명, $3 CLAUDE.md에 넣을 참조 문장
check_deadref() {
  local want="$1" desc="$2" line="$3" tmp got
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/rules" "$tmp/.claude/hooks/lib" "$tmp/docs"
  printf 'x\n' > "$tmp/.claude/hooks/lib/bash-text.sh"
  printf '# rules\n' > "$tmp/.claude/rules/harness-core.md"
  printf '# progress\n' > "$tmp/docs/progress.md"
  printf '%s\n' "$line" > "$tmp/CLAUDE.md"
  ( cd "$tmp" && git init -q . && git add -A >/dev/null 2>&1 )
  if CLAUDE_PROJECT_DIR="$tmp" "$HOOKS/check-doc-staleness.sh" </dev/null 2>/dev/null \
     | jq -r '.hookSpecificOutput.additionalContext // empty' | grep -q '산문이 가리키는데'; then
    got="발화"
  else
    got="침묵"
  fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

echo "── 없는 공용 파일 (check-doc-staleness §0) ──"
#
# `$(dirname "${BASH_SOURCE[0]}")/lib/x.sh` 형태를 못 보면 그 lib의 부재가 **통째로 무보고**다.
# 실제로 그 형태로 source하는 훅 셋을 못 봐서 사용처를 4가 아니라 1로 셌고, 그중 하나가
# 파괴 명령 차단기였다.
# $1 기대(발화/침묵), $2 설명, $3 lib을 실제로 만들 것인가(yes/no)
check_missinglib() {
  local want="$1" desc="$2" create="$3" tmp got
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/hooks/lib" "$tmp/.claude/rules" "$tmp/docs"
  printf '# rules\n' > "$tmp/.claude/rules/harness-core.md"
  printf '# progress\n' > "$tmp/docs/progress.md"
  printf 'x\n' > "$tmp/.claude/hooks/lib/bash-text.sh"
  # 안쪽에 따옴표가 또 있는 형태. 이것이 이 픽스처의 요점이다.
  printf '#!/bin/bash\n. "$(dirname "${BASH_SOURCE[0]}")/lib/ghost.sh"\n' > "$tmp/.claude/hooks/x.sh"
  [ "$create" = yes ] && printf 'x\n' > "$tmp/.claude/hooks/lib/ghost.sh"
  ( cd "$tmp" && git init -q . && git add -A >/dev/null 2>&1 )
  if CLAUDE_PROJECT_DIR="$tmp" "$HOOKS/check-doc-staleness.sh" </dev/null 2>/dev/null \
     | jq -r '.hookSpecificOutput.additionalContext // empty' | grep -q 'ghost.sh'; then
    got="발화"
  else
    got="침묵"
  fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

check_missinglib 발화 "안쪽 따옴표가 있는 source 형태의 부재를 잡는다" no
check_missinglib 침묵 "그 형태라도 파일이 있으면 조용하다"            yes

echo "── 죽은 하네스 참조 (check-doc-staleness §6) ──"
check_deadref 발화 "죽은 완전 경로를 잡는다"        '규칙은 `.claude/rules/harness.md`에 있다.'
# 21곳 중 11곳이 이 형태였다. 완전 경로만 보면 절반이 초록으로 덮인다.
check_deadref 발화 "역따옴표 안 파일명 단독도 잡는다" '분량 규칙은 `harness.md`에 있다.'
check_deadref 침묵 "실재하는 참조는 조용하다"       '규칙은 `.claude/rules/harness-core.md`에 있다.'
check_deadref 침묵 "외부 패키지 표준 파일명은 거른다" 'PrimeVue의 `LICENSE.md`를 본다.'

# 나열 **순서**까지 본다. 기존 네 행은 전부 죽은 참조가 하나뿐이라 순서를 재지 못한다.
#
# §6은 「파일 순서 → 파일 안 정렬」로 낸다. 파일마다 grep을 부르던 것을 목록 일괄로 바꿀 때
# 전역 `sort -u` 하나로 뭉개면 이 순서가 **사전순**으로 바뀌는데, 죽은 참조가 하나뿐이면
# 두 구현이 같은 문장을 내어 그 회귀가 초록으로 덮인다.
# 아래는 파일 순서(a.md → b.md)와 사전 순서(beta → zeta)가 **어긋나게** 배치했다.
check_deadref_order() {
  local desc="파일 순서대로 나열한다(사전순이 아니다)" tmp got want="zeta.md beta.md"
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/rules" "$tmp/.claude/hooks/lib" "$tmp/docs"
  printf 'x\n'          > "$tmp/.claude/hooks/lib/bash-text.sh"
  printf '# core\n'     > "$tmp/.claude/rules/harness-core.md"
  printf '# progress\n' > "$tmp/docs/progress.md"
  printf '분량은 `zeta.md`가 답한다.\n' > "$tmp/.claude/rules/a.md"
  printf '배치는 `beta.md`가 답한다.\n' > "$tmp/.claude/rules/b.md"
  ( cd "$tmp" && git init -q . && git add -A >/dev/null 2>&1 )
  got=$(CLAUDE_PROJECT_DIR="$tmp" "$HOOKS/check-doc-staleness.sh" </dev/null 2>/dev/null \
        | jq -r '.hookSpecificOutput.additionalContext // empty' \
        | grep -o 'zeta\.md beta\.md\|beta\.md zeta\.md' | head -1)
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "순서" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (얻음: %s / 기대: %s)\n' "순서" "$desc" "${got:-없음}" "$want"
  fi
}
check_deadref_order

echo "── 문서 증가 추세 (check-doc-staleness §1c) ──"
#
# 이 절은 **회귀 커버리지가 0이었다.** 그런데 이 검사는 판정이 조용하다 — 발화하지 않는 것이
# 정상 상태라, 로직이 망가져도 "오늘은 걸릴 문서가 없구나"와 구분되지 않는다.
#
# 임시 저장소에 **크기를 통제한 커밋을 실제로 쌓는다.** 기존 픽스처(check_deadref 등)는
# `git add`만 하고 커밋을 안 하는데, 커밋이 없으면 `git log`가 비어 §1c가 통째로 안 돈다 —
# 그 상태로는 이 표가 아무것도 재지 못한다.
#
# MUST: `LC_ALL`을 고정한다. §1c는 `wc -m`(문자)으로 재고 그것은 로케일 의존이다. 무로케일
# 환경에서는 같은 코드가 **바이트**를 세므로, 고정하지 않으면 이 표의 판정이 기계마다 갈린다.
#
# $1 기대(발화/침묵) $2 설명 $3 파일 경로 $4 채울 글자 $5.. 커밋별 글자 수(오래된 순)
check_growth() {
  local want="$1" desc="$2" path="$3" fill="$4"; shift 4
  local tmp got sz
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/rules" "$tmp/docs" "$tmp/$(dirname "$path")"
  printf '# rules\n'    > "$tmp/.claude/rules/harness-core.md"
  printf '# progress\n' > "$tmp/docs/progress.md"
  ( cd "$tmp" && git init -q . ) >/dev/null 2>&1
  for sz in "$@"; do
    awk -v n="$sz" -v c="$fill" 'BEGIN { s=""; for (i=0;i<n;i++) s = s c; print s }' > "$tmp/$path"
    ( cd "$tmp" && git add -A >/dev/null 2>&1 \
      && git -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false \
             commit -q -m x >/dev/null 2>&1 )
  done
  if LC_ALL=ko_KR.UTF-8 CLAUDE_PROJECT_DIR="$tmp" "$HOOKS/check-doc-staleness.sh" </dev/null 2>/dev/null \
     | jq -r '.hookSpecificOutput.additionalContext // empty' | grep -q '줄어든 적이 없는 문서'; then
    got="발화"
  else
    got="침묵"
  fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

# 창(12) 안에서 생긴 파일은 **생성 커밋이 빠진다.** 아래 5커밋 픽스처는 그래서 n=4가 되어
# 겨우 min_points를 채운다 — 커밋을 하나 줄이면 침묵으로 넘어간다(마지막 케이스가 그것이다).
check_growth 발화 "규칙 파일이 순증가로 임계를 넘으면 잡는다" ".claude/rules/x.md"  a  100 400 700 1000 1300
check_growth 침묵 "한 번이라도 줄었으면 조용하다"           ".claude/rules/x.md"  a  100 400 700 600 1300
check_growth 침묵 "순증가지만 임계 미달이면 조용하다"        ".claude/rules/x.md"  a  100 400 500 600 700
check_growth 침묵 "커밋이 모자라면(생성 제외 후 n<4) 조용하다" ".claude/rules/x.md" a  100 400 700 1000

# 임계가 경로마다 다르다(규칙 800 / 그 밖 3000). 하나로 합치면 ADR이 매번 울거나
# 규칙이 영영 안 운다.
check_growth 침묵 "ADR은 +900자로는 조용하다(임계 3000)"    "docs/adr/ADR-0001-x.md" a  100 400 700 1000 1300
check_growth 발화 "ADR도 +3,200자면 잡는다"                "docs/adr/ADR-0001-x.md" a  100 1000 2000 3000 4200

# **이 표에서 가장 중요한 행.** §1c는 `wc -m`(문자)으로 재는데 한글은 문자당 3바이트다.
# 바이트로 재는 구현으로 "더 빠르게" 바꾸면(`git cat-file --batch-check`가 그 유혹이다)
# 아래가 문자 +1,200 / 바이트 +3,600이 되어 **침묵해야 할 자리에서 발화한다.**
check_growth 침묵 "한글 문서를 바이트로 세면 안 된다(문자 +1,200 · 바이트 +3,600)" \
  "docs/adr/ADR-0002-x.md" 가  100 400 800 1200 1600

echo "── SessionStart matcher (설정 회귀) ──"
#
# 이 표에 **설정 파일을 보는 검사가 0건이었다.** 훅 자체가 멀쩡해도 matcher가 좁으면 그 훅은
# 그냥 안 돈다. 그리고 **무효한 matcher 값은 에러 없이 아무것도 매칭하지 않으므로** 누가
# 좁혀 놓아도 아무 신호가 없다 — 실제로 `compact` 직후, 즉 상태 주입이 가장 필요한 순간에
# progress.md가 안 들어오는 상태로 오래 있었다.
#
# 유효 값은 다섯이다: startup · resume · clear · compact · fork.
#
# MUST: **항목마다** 다섯을 다 요구한다. 합집합으로 보면 일부러 좁힌 항목 하나가 다른 항목
# 뒤에 숨는다. 좁힌 항목을 정말 두기로 했으면 이 검사를 고치되, **고치는 그 자리가 「왜
# 좁혔는가」를 적을 자리다.**
#
# $1 설명, $2 settings.json 경로, $3 없을 때(skip|require)
check_sessionstart_matcher() {
  local desc="$1" path="$2" mode="$3" n i m v missing
  if [ ! -f "$path" ]; then
    if [ "$mode" = skip ]; then
      skipped=$((skipped+1)); printf '  \033[33mSKIP\033[0m %s (설정 파일이 없다)\n' "$desc"; return
    fi
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (설정 파일이 없다)\n' "$desc"; return
  fi
  n=$(jq -r '.hooks.SessionStart | length' "$path" 2>/dev/null)
  if ! [ "${n:-0}" -gt 0 ] 2>/dev/null; then
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (SessionStart 항목이 없거나 JSON이 깨졌다)\n' "$desc"; return
  fi
  for ((i = 0; i < n; i++)); do
    m=$(jq -r --argjson i "$i" '.hooks.SessionStart[$i].matcher // ""' "$path")
    missing=""
    for v in startup resume clear compact fork; do
      printf '%s' "|$m|" | grep -q "|$v|" || missing="$missing $v"
    done
    if [ -z "$missing" ]; then
      pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s [%d]\n' "다섯" "$desc" "$i"
    else
      fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s [%d]  (빠진 값:%s)\n' "누락" "$desc" "$i" "$missing"
    fi
  done
}

check_sessionstart_matcher "저장소 SessionStart가 다섯을 다 덮는다" ".claude/settings.json"       require
# 전역은 이 저장소가 아니라 기계에 딸린 것이라 다른 기계에서는 없을 수 있다. 없으면 건너뛰되
# **통과로 세지 않는다** — 건너뜀이 통과로 읽히면 덮여 있다고 믿게 된다.
check_sessionstart_matcher "전역 SessionStart가 다섯을 다 덮는다"   "$HOME/.claude/settings.json" skip

echo "── 포인터 훅이 낸 규칙의 집합 (remind-rule-on-*.sh) ──"
#
# 위 「규칙 포인터 알림」 그룹은 **발화 여부만** 본다. 그래서 `remind-rule-on-shell.sh`가 세 규칙
# 중 **하나만** 내던 회귀가 이 표에 초록으로 남아 있었다(평탄 목록을 안쪽 루프에 놓고 첫 매칭에서
# break 했고, 순회가 사전순이라 늘 같은 하나가 이겼다). 침묵이 아니라 **부분 발화**라 발화/침묵
# 두 값으로는 원리적으로 못 가른다.
#
# MUST: 호출마다 **새 session_id**를 만든다. 훅이 규칙별로 세션당 1회만 내므로 고정 id를 쓰면
# 2회차부터 빈 집합이 나오고, 이 행이 조용히 뒤집힌다(실측: 같은 id 2회 → 3개 / 0개).
#
# 기대 집합은 세 규칙의 `paths`가 `.claude/hooks/**`를 덮어서 나온 값이다.
# **그 glob을 고치면 이 줄도 함께 고친다.** check-doc-staleness.sh §4가 glob이 죽는 것은 잡지만
# 대상이 늘고 주는 것은 잡지 않는다.
#
# $1 기대 집합(공백 구분·사전순), $2 설명, $3 대상(명령 또는 경로), $4 훅(rule|write)
check_rule_set() {
  local want="$1" desc="$2" target="$3" hook="$4" got out key script
  case "$hook" in
    rule)  key=command;   script=remind-rule-on-shell.sh ;;
    write) key=file_path; script=remind-rule-on-write.sh ;;
    *)     fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (알 수 없는 훅: %s)\n' "$desc" "$hook"; return ;;
  esac
  out=$(jq -n --arg v "$target" --arg k "$key" --arg s "sess-$RANDOM$RANDOM" \
        '{session_id:$s, tool_input:{($k):$v}}' | "$HOOKS/$script" 2>/dev/null)
  got=$(printf '%s' "$out" | jq -r '.hookSpecificOutput.additionalContext // ""' 2>/dev/null \
        | grep -oE 'rules/[a-z-]+\.md' | sed 's|rules/||' | sort -u | tr '\n' ' ')
  got="${got% }"
  [ -z "$got" ] && got="(없음)"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "집합" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s\n            얻음: %s\n            기대: %s\n' "집합" "$desc" "$got" "$want"
  fi
}

RULESET_HOOKS_LIB="doc-facts.md harness-core.md harness-hooks.md"
check_rule_set "$RULESET_HOOKS_LIB" "셸 읽기가 지배 규칙 셋을 다 낸다" \
  "grep -n pgrep .claude/hooks/lib/load-process.sh" rule
check_rule_set "$RULESET_HOOKS_LIB" "셸 편집(sed -i)도 셋을 다 낸다" \
  "sed -i '' 's/a/b/' .claude/hooks/lib/load-process.sh" rule
check_rule_set "$RULESET_HOOKS_LIB" "Write 경로도 셋을 다 낸다" \
  ".claude/hooks/lib/load-process.sh" write

echo "── 발견의 갈래 상기 (remind-finding-triage) ──"
# MUST NOT: 이 훅에 세션당 억제를 넣지 않는다. 값을 만드는 것이 **반복**이라 한 번만 뜨면
# 세션 시작 주입과 같아진다(그 희석이 이 훅을 만든 이유다). 그래서 「두 번째 커밋도 운다」가 행이다.
check 발화 "커밋에 뜬다"              "Bash:git commit -m x"                    triage
check 발화 "두 번째 커밋도 뜬다"       "Bash:git commit -m y"                    triage
check 발화 "amend에도 뜬다"           "Bash:git commit --amend --no-edit"       triage
check 발화 "질문에 뜬다"              "AskUserQuestion:"                        triage
check 침묵 "읽기만 하는 git엔 안 뜬다" "Bash:git log --oneline --grep=commit"    triage
check 침묵 "일반 명령엔 안 뜬다"       "Bash:./gradlew build"                    triage
check 침묵 "다른 도구엔 안 뜬다"       "Edit:docs/progress.md"                   triage

echo "── 적재 잡 이름 거울 (check-doc-staleness §5c) ──"
#
# 저장소를 건드리지 않는다. 다섯 자리를 임시 트리에 복사해 거기서 비튼다.
# $1 기대(발화/침묵), $2 설명, $3 비틀 파일(트리 기준 경로, 없으면 원본 그대로), $4 perl 치환식
check_jobnames() {
  local want="$1" desc="$2" target="${3:-}" expr="${4:-}" tmp got runner
  runner=$(find src/main/java -name BootstrapRunner.java -type f 2>/dev/null | head -1 || true)
  if [ -z "$runner" ]; then
    skipped=$((skipped+1)); printf '  \033[33mSKIP\033[0m %s (BootstrapRunner.java가 없다)\n' "$desc"; return
  fi
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/rules" "$tmp/.claude/hooks/lib" "$tmp/.claude/skills/batch-load" \
           "$tmp/docs" "$tmp/src/main/java/x"
  printf 'x\n' > "$tmp/.claude/hooks/lib/bash-text.sh"
  printf '# rules\n'    > "$tmp/.claude/rules/harness-core.md"
  printf '# progress\n' > "$tmp/docs/progress.md"
  cp "$runner"                                  "$tmp/src/main/java/x/BootstrapRunner.java"
  cp .claude/hooks/lib/load-process.sh          "$tmp/.claude/hooks/lib/"
  cp .claude/hooks/guard-rebuild-without-dump.sh "$tmp/.claude/hooks/"
  cp .claude/skills/batch-load/SKILL.md         "$tmp/.claude/skills/batch-load/"
  [ -n "$target" ] && perl -i -pe "$expr" "$tmp/$target"
  if CLAUDE_PROJECT_DIR="$tmp" "$HOOKS/check-doc-staleness.sh" </dev/null 2>/dev/null \
     | jq -r '.hookSpecificOutput.additionalContext // empty' \
     | grep -q '적재 잡 이름\|guard-rebuild-without-dump.sh`가'; then
    got="발화"
  else
    got="침묵"
  fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

check_jobnames 침묵 "다섯 자리가 맞으면 조용하다"
# MUST NOT: 목록의 **마지막** 이름을 지우는 식으로 비틀지 않는다. 잡을 하나 더하면 그 이름이
# 더는 마지막이 아니라 치환이 no-op이 되고, 이 행이 조용히 FAIL로 뒤집힌다 — 하필 거울 검사를
# 지키는 행이다. 가운데 이름을 지워 순서에 안 기대게 한다.
check_jobnames 발화 "훅의 pgrep 목록에서 잡이 빠지면 잡는다" \
  ".claude/hooks/lib/load-process.sh" 's/initial-load\|//'
# 아래 둘이 이 그룹의 요점이다. frontmatter와 본문을 합집합으로 보면 **둘 다 침묵으로 뒤집힌다** —
# `description`은 매 세션 상주하는 유일한 부분이라 그쪽이 낡는 것이 더 나쁘다.
check_jobnames 발화 "skill의 frontmatter만 낡아도 잡는다" \
  ".claude/skills/batch-load/SKILL.md" 's/\|market-index\|/\|/ if /^description:/'
check_jobnames 발화 "skill의 본문만 낡아도 잡는다" \
  ".claude/skills/batch-load/SKILL.md" 's/^--job=market-index.*\n//'
# 차단 가드는 **부분집합**이 정상이다. 이름이 갈리는 것만 잡아야 하고, 둘뿐인 것을 잡으면 안 된다.
check_jobnames 발화 "차단 가드의 잡 이름 오타를 잡는다" \
  ".claude/hooks/guard-rebuild-without-dump.sh" 's/financial-refill\*\)/financial-refil*)/'

echo "── 이름으로 부른 절 (check-doc-staleness §6b) ──"
#
# **이 그룹은 `src/`가 없는 트리에서 돈다.** 그것이 의도다 — §5c가 `find src/main/java`의 실패를
# `|| true` 없이 받으면 `set -e`가 훅을 통째로 죽여 §6·§6b가 사라지는데, 그 침묵은 "불일치 없음"과
# 구분되지 않는다. 실제로 그 상태로 한 번 작성됐고 이 그룹이 잡았다.
#
# $1 기대(발화/침묵), $2 설명, $3 progress.md 본문, $4 규칙 파일 본문
check_section() {
  local want="$1" desc="$2" prog="$3" rule="$4" tmp got
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/rules" "$tmp/.claude/hooks/lib" "$tmp/docs"
  printf 'x\n' > "$tmp/.claude/hooks/lib/bash-text.sh"
  printf '%s\n' "$prog" > "$tmp/docs/progress.md"
  printf '%s\n' "$rule" > "$tmp/.claude/rules/harness-core.md"
  ( cd "$tmp" && git init -q . && git add -A >/dev/null 2>&1 )
  if CLAUDE_PROJECT_DIR="$tmp" "$HOOKS/check-doc-staleness.sh" </dev/null 2>/dev/null \
     | jq -r '.hookSpecificOutput.additionalContext // empty' | grep -q '제목으로 실재하지 않는 절'; then
    got="발화"
  else
    got="침묵"
  fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

check_section 침묵 "부르는 절이 제목으로 있으면 조용하다" \
  "$(hd '# 진행 상태' '' '## 사용자 판단이 먼저인 것 — 묻고 시작한다' '' '내용')" \
  "$(hd '# rules' '' '「사용자 판단이 먼저인 것」 절을 비었다고 지우지 않는다.')"
# `104d79b`가 실제로 낸 사고. 절과 함께 그 안의 미결 질문이 증발했고 아무 검사도 울지 않았다.
check_section 발화 "부르는 절이 통째로 사라지면 잡는다" \
  "$(hd '# 진행 상태' '' '## 다른 절만 있다')" \
  "$(hd '# rules' '' '「사용자 판단이 먼저인 것」 절을 비었다고 지우지 않는다.')"
# 제목이 부르는 이름보다 길어도 해소다. 완전일치로 바꾸면 이 행이 붉어진다 — 저장소의 실제
# 참조 하나가 그 형태다(제목에 뒤가 더 붙어 있다).
check_section 침묵 "제목이 더 길어도 해소로 본다" \
  "$(hd '# 진행 상태' '' '#### 백테스트가 뒤집을 수 있다고 적었으나, 못 뒤집는다 — 실측')" \
  "$(hd '# rules' '' '아래 「백테스트가 뒤집을 수 있다」 절이 그 실측을 든다.')"

# **오탐 쪽을 재는 행이다.** §6b가 제목 목록을 `printf … | grep -q`로 먹이면 `pipefail` 아래에서
# **매치가 앞쪽에 걸릴 때만** SIGPIPE로 141이 나 실재하는 절이 「사라졌다」로 신고된다. 위 셋은
# 제목이 몇 줄뿐이라 그 상태로도 전부 통과한다 — **부피가 있어야 재진다.** 부르는 이름을 정렬
# 맨 앞에 두고 뒤에 제목을 잔뜩 깔아 그 조건을 만든다(파이프 버퍼 64KB를 넘겨야 한다).
check_section 침묵 "제목이 많고 매치가 앞쪽이어도 조용하다" \
  "$(hd '# AAA 아주 앞선 제목'; for i in $(seq 1 20000); do printf '## zzz %s\n' "$i"; done)" \
  "$(hd '# rules' '' '「AAA 아주 앞선 제목」 절을 지우지 않는다.')"

echo "── 진행 상태 주입 (inject-progress.sh · check-doc-staleness §1) ──"
#
# 이 그룹이 생기기 전까지 **주입 훅을 재는 행이 하나도 없었다.** 세션 간 전달 경로는
# 훅과 progress.md 둘뿐인데 그 하나가 통째로 무측정이었다.
#
# MUST: 훅을 임시 트리로 **복사해서** 돌린다. inject-progress.sh는 lib을 `BASH_SOURCE`
# 기준으로 찾으므로, 저장소의 훅을 그대로 부르면 **저장소의 lib**을 읽어 "lib이 없을 때"를
# 원리적으로 못 잰다.
# MUST: `load-process.sh`를 복사하지 않는다. 복사하면 픽스처가 실제 프로세스를 뒤져
# 적재가 도는 동안 앞머리에 다른 문장이 붙는다 — 그래서 자수가 아니라 문자열로 잰다.

# 백로그 절을 채운 진행 상태. 파일은 경고선(4,000)을 넘고 주입분은 안 넘는 모양이다.
# MUST: 패딩을 ASCII로 만든다. 한글로 채우면 BSD awk의 `length()`가 **바이트**를 세어
# 목표의 3분의 1만 나오고, 그러면 파일이 경고선에 못 미쳐 **발화 행도 침묵 행도 전부
# 거짓 통과**한다(실제로 그렇게 통과했다). §1이 재는 것은 `wc -m`, 즉 문자 수다.
_pad=$(awk 'BEGIN{ while (i++ < 5000) printf "x" }')
_prog_with=$(hd '# 진행 상태' '' '## 지금 어디까지 왔는가' '' '상태 한 줄.' '' \
  '## 다음에 할 일' '' '### 바로 착수 가능한 것' '' "- 백로그 하나. ${_pad}" '- 백로그 둘.' '' \
  '## 되돌리지 말 것' '' '뒤 절은 남아야 한다.')
# MUST NOT: `${var/### …/…}`로 제목만 바꾸려 하지 않는다. 패턴 첫 글자 `#`를 bash가
# **앵커 수식자**로 먹어 치환이 조용히 안 일어난다 — 픽스처 둘이 같아져 발화 행이 전부
# 침묵으로 통과한다(실제로 그렇게 세 행이 거짓 통과했다). 제목이 다른 본문을 따로 짓는다.
_prog_renamed=$(hd '# 진행 상태' '' '## 지금 어디까지 왔는가' '' '상태 한 줄.' '' \
  '## 다음에 할 일' '' '### 이름을 바꿨다' '' "- 백로그 하나. ${_pad}" '- 백로그 둘.' '' \
  '## 되돌리지 말 것' '' '뒤 절은 남아야 한다.')

check_inject() {
  local want="$1" desc="$2" prog="$3" pat="$4" withlib="${5:-yes}" tmp got
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/hooks/lib" "$tmp/docs"
  cp "$HOOKS/inject-progress.sh" "$tmp/.claude/hooks/" 2>/dev/null
  [ "$withlib" = yes ] && cp "$HOOKS/lib/progress-section.sh" "$tmp/.claude/hooks/lib/" 2>/dev/null
  printf '%s\n' "$prog" > "$tmp/docs/progress.md"
  if CLAUDE_PROJECT_DIR="$tmp" bash "$tmp/.claude/hooks/inject-progress.sh" </dev/null 2>/dev/null \
     | jq -r '.hookSpecificOutput.additionalContext // empty' | grep -qF -- "$pat"; then
    got="포함"
  else
    got="제외"
  fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

check_inject 제외 "절이 있으면 백로그 본문이 주입에 안 실린다" "$_prog_with" "백로그 둘."
check_inject 포함 "그때 항목 수를 한 줄로 낸다"               "$_prog_with" "2개는 주입에서 뺐다"
check_inject 포함 "뒤 절은 주입에 남는다"                     "$_prog_with" "되돌리지 말 것"
# 제목이 바뀌면 **전부 주입**한다. 조용히 다 빼는 쪽이 무성실패다 — 백로그가 사라진 것이
# 「항목 0」과 구분되지 않는다.
check_inject 포함 "절 제목이 바뀌면 전부 주입한다"            "$_prog_renamed" "백로그 둘."
check_inject 포함 "그리고 못 찾았다고 말한다"                 "$_prog_renamed" "절을 못 찾아 전부 주입했다"
check_inject 포함 "lib이 없어도 주입은 죽지 않는다"           "$_prog_with" "백로그 둘." no
check_inject 포함 "그리고 lib이 없다고 말한다"                "$_prog_with" "필터 없이 전부 주입했다" no

# §1은 **주입분**을 재야 한다. 파일 전체를 재면 필터를 넣은 뒤에도 헛경보가 난다.
check_progsize() {
  local want="$1" desc="$2" prog="$3" tmp got
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/hooks/lib" "$tmp/docs" "$tmp/.claude/rules"
  printf 'x\n' > "$tmp/.claude/hooks/lib/bash-text.sh"
  cp "$HOOKS/inject-progress.sh" "$tmp/.claude/hooks/" 2>/dev/null
  cp "$HOOKS/lib/progress-section.sh" "$tmp/.claude/hooks/lib/" 2>/dev/null
  printf '%s\n' "$prog" > "$tmp/docs/progress.md"
  ( cd "$tmp" && git init -q . && git add -A >/dev/null 2>&1 )
  if CLAUDE_PROJECT_DIR="$tmp" "$HOOKS/check-doc-staleness.sh" </dev/null 2>/dev/null \
     | jq -r '.hookSpecificOutput.additionalContext // empty' | grep -q '주입분이'; then
    got="발화"
  else
    got="침묵"
  fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

check_progsize 침묵 "백로그가 커도 주입분이 작으면 안 운다" "$_prog_with"
# 필터가 풀리면 그 큰 파일이 통째로 주입되므로 그때는 울어야 한다.
check_progsize 발화 "절 제목이 바뀌면 주입분이 커져 운다"   "$_prog_renamed"

echo "── 사전등록 임계 거울 (check-doc-staleness §5d) ──"
#
# 이 그룹이 붙드는 것은 발화 여부만이 아니다. §5d에는 **조용히 뚫리는 형태가 둘** 있고 둘 다
# 아래 케이스가 없으면 초록으로 덮인다 — ① 등록 표 값의 끝을 안 닫으면 `≤ 0`이 `≤ 0.10`에
# 매치한다 ② 같은 (연산자,값)이 코드 여러 곳에 있을 때 한 곳만 바뀌면 나머지가 grep을 만족시킨다.
#
# $1 기대(발화/침묵), $2 설명, $3 비틀 파일(트리 기준), $4 perl 치환식
check_preset_reg() {
  local want="$1" desc="$2" target="${3:-}" expr="${4:-}" tmp got src
  src=$(find src/main/java -name PresetCatalog.java -type f 2>/dev/null | head -1 || true)
  if [ -z "$src" ] || [ ! -f docs/phase-criteria.md ]; then
    skipped=$((skipped+1)); printf '  \033[33mSKIP\033[0m %s (PresetCatalog.java 또는 등록 표가 없다)\n' "$desc"; return
  fi
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/rules" "$tmp/.claude/hooks/lib" "$tmp/docs" "$tmp/src/main/java/x"
  printf 'x\n' > "$tmp/.claude/hooks/lib/bash-text.sh"
  printf '# rules\n'    > "$tmp/.claude/rules/harness-core.md"
  printf '# progress\n' > "$tmp/docs/progress.md"
  cp "$src"                   "$tmp/src/main/java/x/PresetCatalog.java"
  cp docs/phase-criteria.md   "$tmp/docs/phase-criteria.md"
  [ -n "$target" ] && perl -i -pe "$expr" "$tmp/$target"
  if CLAUDE_PROJECT_DIR="$tmp" "$HOOKS/check-doc-staleness.sh" </dev/null 2>/dev/null \
     | jq -r '.hookSpecificOutput.additionalContext // empty' | grep -q '등록한 절대 임계'; then
    got="발화"
  else
    got="침묵"
  fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

check_preset_reg 침묵 "코드와 등록 표가 맞으면 조용하다"
check_preset_reg 발화 "코드 리터럴이 바뀌면 잡는다" \
  "src/main/java/x/PresetCatalog.java" 's/ComparisonOperator\.LTE, "5\.9"/ComparisonOperator.LTE, "4.9"/'
check_preset_reg 발화 "등록 표의 값이 바뀌면 잡는다" \
  "docs/phase-criteria.md" 's/\| ≤ 5\.9 \|/| ≤ 4.9 |/'
#
# **이 표에서 가장 중요한 두 행.** 값 끝을 안 닫거나 개수를 안 세면 둘 다 침묵으로 뒤집힌다.
# 위쪽은 ADR-0061 §규칙이 「초안의 발생액 ≤0.10이 96% 통과였다」로 적어 둔 되돌림 경로다.
check_preset_reg 발화 "등록 표가 ≤ 0 → ≤ 0.10 이면 잡는다(접두 일치 금지)" \
  "docs/phase-criteria.md" 's/\| ≤ 0 \|/| ≤ 0.10 |/'
check_preset_reg 발화 "같은 값 5곳 중 1곳만 바뀌어도 잡는다(개수 보존)" \
  "src/main/java/x/PresetCatalog.java" 'if (!$done && s/AccrualRatioCalculator\.CODE, ComparisonOperator\.LTE, "0"/AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0.05"/) { $done = 1 }'
# 열거식이라 표에만 있는 이름은 안 본다. 그것이 설계다 — 나중 결정이 정당하게 갈아엎은 행이
# 표에 남아 있고, 전부 같기를 요구하면 그 넷이 매 세션 오탐이 된다.
check_preset_reg 침묵 "표에만 있고 열거에 없는 행은 안 본다" \
  "docs/phase-criteria.md" 's/^\| `ALTMAN_Z`/| `GHOST_METRIC` | >= 1 | x |\n| `ALTMAN_Z`/'

echo "── 전제 러너 (check-premises) ──"
#
# 저장소의 ADR이 아니라 **임시 트리의 가짜 전제**로 돈다. DB가 필요 없고, 실제 전제의 발견이
# 「전부 초록」 갈래를 가로막지 않는다(실제 트리는 늘 무언가 발화 중이라 그 갈래를 못 밟는다).
# 가드는 진짜를 복사한다 — 심사할 수 없으면 run_one이 돌지 않는다.
# 예산은 `SECONDS` 환경값으로 앞당긴다. bash 3.2가 그것을 환경에서 받는다(실측 SECONDS=70 → 70).
#
# $1 기대(포함/제외), $2 설명, $3 찾을 정규식(grep -E), $4 어디서 찾나(ctx/capture/first/stderr),
# $5 전제 묶음(two/pgopts/sleep7), $6.. 환경 대입
check_premise_runner() {
  local want="$1" desc="$2" pat="$3" where="$4" set="$5" tmp got hay
  shift 5
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  mkdir -p "$tmp/.claude/hooks/lib" "$tmp/docs/adr"
  cp "$HOOKS/check-premises.sh" "$HOOKS/guard-destructive.sh" "$tmp/.claude/hooks/"
  cp "$HOOKS/lib/bash-text.sh" "$HOOKS/lib/load-process.sh" "$tmp/.claude/hooks/lib/"
  case "$set" in
    two)
      hd '## 검증' '' '```premise 오래된 느린 전제' 'sleep 0.3; echo OK' '```' > "$tmp/docs/adr/ADR-0001-old.md"
      hd '## 검증' '' '```premise 새 빠른 전제' 'echo OK' '```' > "$tmp/docs/adr/ADR-0002-new.md" ;;
    pgopts)
      hd '## 검증' '' '```premise 질의 상한' 'echo "$PGOPTIONS"' '```' > "$tmp/docs/adr/ADR-0001-opts.md" ;;
    sleep7)
      hd '## 검증' '' '```premise 상한을 넘는 전제' 'sleep 7; echo OK' '```' > "$tmp/docs/adr/ADR-0001-slow.md" ;;
  esac
  hay=$(cd "$tmp" && env CLAUDE_PROJECT_DIR="$tmp" PREMISE_CAPTURE= "$@" \
          bash "$tmp/.claude/hooks/check-premises.sh" </dev/null 2>"$tmp/stderr")
  case "$where" in
    ctx)     hay=$(printf '%s' "$hay" | jq -r '.hookSpecificOutput.additionalContext // empty' 2>/dev/null) ;;
    capture) hay=$(cat "$tmp/capture.tsv" 2>/dev/null) ;;
    # 첫 줄만 — 순서를 물을 때. `^`는 모든 줄 머리에 걸려 전체 파일로는 「첫째」를 못 묻는다.
    first)   hay=$(sed -n 1p "$tmp/capture.tsv" 2>/dev/null) ;;
    stderr)  hay=$(cat "$tmp/stderr") ;;
  esac
  if printf '%s' "$hay" | grep -qE -- "$pat"; then got="포함"; else got="제외"; fi
  rm -rf "$tmp"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

# 캡처 경로는 `PREMISE_CAPTURE`를 임시 트리 안의 파일로 준다 — 상대 경로라 훅의 cd 뒤에 풀린다.
check_premise_runner 제외 "전부 OK이고 예산이 넉넉하면 아무것도 안 낸다" \
  '전제를 확인했다' ctx two
check_premise_runner 포함 "예산 알림이 뜨면 가장 오래 걸린 것을 맨 앞에 이름으로 댄다" \
  '가장 오래 걸린 다섯: ADR-0001-old\.md — 오래된 느린 전제 0\.[0-9]+초' ctx two SECONDS=70
check_premise_runner 포함 "지명할 때 그 순간의 조건을 함께 낸다" \
  '그때 조건: .*load' ctx two SECONDS=70
check_premise_runner 포함 "새 ADR부터 돈다(번호 역순)" \
  '^ADR-0002-new\.md' first two PREMISE_CAPTURE=capture.tsv
check_premise_runner 포함 "경과 파일을 못 만들면 그렇다고 말한다" \
  '시간을 재지 못했다' ctx two SECONDS=70 TMPDIR=/nonexistent-premise-dir
check_premise_runner 포함 "캡처 손잡이가 질의 상한을 올린다" \
  'statement_timeout=120s' capture pgopts PREMISE_CAPTURE=capture.tsv PREMISE_STATEMENT_TIMEOUT=120
check_premise_runner 포함 "SessionStart 경로에서는 손잡이를 안 듣는다" \
  'statement_timeout=6s' ctx pgopts PREMISE_STATEMENT_TIMEOUT=120
check_premise_runner 포함 "손잡이가 숫자가 아니면 거부한다" \
  '양의 정수' stderr pgopts PREMISE_CAPTURE=capture.tsv PREMISE_STATEMENT_TIMEOUT=abc
# 손잡이를 1초로 내리면 alarm 상한이 4+1+1=6초가 된다 — 기본 11초였다면 7초 전제가 끝났을 것이다.
# 같은 행이 **잘린 전제에도 경과가 남는지**(ALRM 경로)를 함께 본다. 6초가 걸린다.
check_premise_runner 포함 "손잡이가 alarm 상한도 함께 옮기고, 잘린 전제도 경과가 남는다" \
  $'\t6\\.[0-9]+\t<출력 없음 rc=142>' capture sleep7 PREMISE_CAPTURE=capture.tsv PREMISE_STATEMENT_TIMEOUT=1

echo
printf '통과 %d / 실패 %d / 건너뜀 %d / 알려진 구멍 %d\n' "$pass" "$fail" "$skipped" "$holes"
# MUST: 건너뛴 수를 함께 낸다. 건너뛴 것이 통과로 읽히면 "덮여 있다"고 믿게 된다 —
# 이 표가 잡으려는 병과 같은 병이다.
[ "$fail" -eq 0 ] || exit 1
