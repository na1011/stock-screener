#!/bin/bash
# Bash 훅 회귀표. `bash .claude/hooks/lib/regression.sh` 로 한 번에 돌린다.
#
# 왜 스크립트로 남기는가 — 이 훅들은 **오탐과 침묵이 정반대 방향으로 나쁘고**, 한쪽만 보면
# 반대쪽이 무너진 것을 모른다. 다음에 이 훅을 만지는 세션이 같은 표를 한 번에 돌릴 수 있어야
# 한다.
#
# IMPORTANT — 위험 문자열은 **런타임에 조립한다.** 이 파일에 `rm -rf`를 그대로 적으면
# 이 스크립트를 실행하는 Bash 명령이 guard-destructive 에 막힌다. 2026-08-09에 실제로 그렇게
# 막혔고, 그 사건이 이 회귀표가 필요한 이유의 절반이다.
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}" || exit 1
HOOKS=.claude/hooks

command -v jq >/dev/null 2>&1 || { echo "jq가 없어 회귀표를 돌릴 수 없다."; exit 1; }

R=$(printf 'r%s' 'm'); F=$(printf -- '-r%s' 'f')
G=$(printf 'g%s' 'it'); FO=$(printf -- '--f%s' 'orce'); FWL=$(printf -- '--f%s' 'orce-with-lease')
DT=$(printf 'dr%s' 'op table'); E=$(printf '.e%s' 'nv'); CT=$(printf 'c%s' 'at')
GR=$(printf 'gr%s' 'ep'); TL=$(printf 't%s' 'ail'); B64=$(printf 'base%s' '64')
JOB=$(printf -- '--j%s' 'ob=initial-load')

pass=0; fail=0

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
  esac
  [ -z "$got" ] && got="(빈응답)"
  if [ "$got" = "$want" ]; then
    pass=$((pass+1)); printf '  \033[32mOK\033[0m   %-8s %s\n' "$got" "$desc"
  else
    fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %-8s %s  (기대: %s)\n' "$got" "$desc" "$want"
  fi
}

hd() { printf '%s\n' "$@"; }

echo "── 실제 파괴 명령은 막혀야 한다 ──"
check deny "실제 파괴 삭제"                    "$R $F build" guard
check deny "실제 force push"                   "$G push $FO" guard
check pass "force-with-lease 는 허용"          "$G push $FWL" guard
check deny "psql -c 파괴 SQL"                  "psql -d x -c \"$DT y\"" guard

echo "── 실행되는 heredoc 본문은 남아야 한다 (무조건 제거하면 깨진다) ──"
check deny "bash <<EOF 본문에 파괴 삭제"        "$(hd 'bash <<EOF' "$R $F /tmp/x" 'EOF')" guard
check deny "sh -s <<EOF 본문에 파괴 삭제"       "$(hd 'sh -s <<EOF' "$R $F /tmp/x" 'EOF')" guard
check deny "heredoc 종료 뒤의 실제 파괴 삭제"   "$(hd "$G commit -F - <<MSG" 'msg' 'MSG' "$R $F /tmp/x")" guard
check deny "psql <<SQL 본문에 파괴 SQL"         "$(hd 'psql -d x <<SQL' "$DT y;" 'SQL')" guard
check deny "psql <<-SQL (탭 허용) 본문"         "$(hd 'psql -d x <<-SQL' "	$DT y;" '	SQL')" guard

echo "── 문서화된 명령은 막지 않아야 한다 ──"
check pass "heredoc 본문에 파괴 삭제 문서화"    "$(hd "$G commit -F - <<MSG" 'fix: 정리' '' "$R $F build/ 를 돌려야 복제본이 사라진다" 'MSG')" guard
check pass "heredoc 본문에 force push 문서화"   "$(hd "$G commit -F - <<MSG" 'docs: 금지' '' "$G push $FO 는 금지다" 'MSG')" guard
check pass "여러 줄 -m 메시지에 파괴 명령"      "$(hd "$G commit -m '정리:" "$R $F build/ 설명'")" guard
check pass "heredoc 본문에 파괴 SQL 문서화"     "$(hd "$G commit -F - <<MSG" 'docs: SQL' '' "$DT 는 금지다" 'MSG')" guard

echo "── 시크릿 읽기는 확인을 요구해야 한다 ──"
check ask  "cat .env"                          "$CT $E" guard
check ask  "cp .env 복사"                      "cp $E /tmp/x" guard
check ask  "base64 ssh 키"                     "$B64 ~/.ssh/id_rsa" guard
check ask  "heredoc 뒤에 숨긴 cat .env"        "$(hd "$G commit -F - <<MSG" 'msg' 'MSG' "$CT $E")" guard
check ask  "상대경로 secrets/"                 "$GR KEY secrets/prod.yaml" guard

echo "── 시크릿과 무관한 명령은 조용해야 한다 ──"
check pass "예시 파일 조회"                    "$GR -n SENTRY ${E}.example" guard
check pass "push | tail (+메시지에 .env 언급)" "$(hd "$G add x" "$G commit -F - <<MSG" "${E}가 test에 없으므로 평가되지 않는다" 'MSG' "$G push 2>&1 | $TL -3")" guard
check pass "checkEnv | tail"                   "./gradlew checkEnv 2>&1 | $TL -5" guard

echo "── 적재 인자 알림 ──"
check 침묵 "적재 명령을 문서화한 커밋 메시지"  "$(hd "$G commit -F - <<MSG" 'docs: 절차' '' "./gradlew bootRun --args='--spring.profiles.active=bootstrap $JOB'" 'MSG')" bootstrap
check 발화 "실제 적재 실행(플래그 없음)"        "./gradlew bootRun --args='--spring.profiles.active=bootstrap $JOB'" bootstrap
check 침묵 "실제 적재 실행(플래그 있음)"        "./gradlew bootRun --args='--spring.profiles.active=bootstrap $JOB --reload-existing=true'" bootstrap

echo "── 규칙 포인터 알림 ──"
check 발화 "셸로 build.gradle 읽기"            "$GR -n bootRun build.gradle" rule
check 침묵 "경로 없는 명령"                    "./gradlew build" rule
check 침묵 "git add 로 이름만 부름"            "$G add docs/progress.md" rule
check 침묵 "커밋 메시지가 경로를 언급"          "$(hd "$G commit -F - <<MSG" 'fix: x' '' 'CLAUDE.md 금지 절에 적었다' 'MSG')" rule
check 발화 "heredoc 뒤의 실제 셸 읽기"          "$(hd "$G commit -F - <<MSG" 'msg' 'MSG' "$GR -n x build.gradle")" rule

echo
printf '통과 %d / 실패 %d\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
