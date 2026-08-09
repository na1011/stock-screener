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
    # 이 분기만 `command`가 아니라 `file_path`를 먹인다 — Edit·Write 경로의 훅이라 입력
    # 모양이 다르다. `$cmd` 자리에 경로를 넘긴다.
    write)
      out=$(jq -n --arg p "$cmd" --arg s "sess-$RANDOM$RANDOM" '{session_id:$s,tool_input:{file_path:$p}}' \
            | "$HOOKS/remind-rule-on-write.sh" 2>/dev/null)
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

echo "── 시크릿과 무관한 명령은 조용해야 한다 ──"
check pass "예시 파일 조회"                    "grep -n SENTRY .env.example" guard
check pass "push | tail (+메시지에 .env 언급)" "$(hd 'git add x' 'git commit -F - <<MSG' '.env가 test에 없으므로 평가되지 않는다' 'MSG' 'git push 2>&1 | tail -3')" guard
check pass "checkEnv | tail"                   "./gradlew checkEnv 2>&1 | tail -5" guard

echo "── 적재 인자 알림 ──"
check 침묵 "적재 명령을 문서화한 커밋 메시지"  "$(hd 'git commit -F - <<MSG' 'docs: 절차' '' "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load'" 'MSG')" bootstrap
check 발화 "실제 적재 실행(플래그 없음)"        "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load'" bootstrap
check 침묵 "실제 적재 실행(플래그 있음)"        "./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load --reload-existing=true'" bootstrap

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
check 발화 "git apply 로 패치"                  "git apply /tmp/p.patch .claude/rules/harness.md" rule
check 발화 "전역 사본 편집"                     "sed -i '' 's/a/b/' ~/.claude/hooks/guard-secrets.sh" rule
check 발화 "find -exec 로 트리 일괄 편집"        "find .claude/hooks -name '*.sh' -exec sed -i '' 's/a/b/' {} +" rule
check 발화 "인터프리터 heredoc 이 파일을 씀"     "$(hd 'python3 - <<PY' "open('.claude/hooks/x.sh','w').write('x')" 'PY')" rule
check 침묵 "git diff 로 조회"                   "git diff .claude/rules/harness.md" rule

echo "── 읽지 않고 편집한 경로 (Write 구멍) ──"
#
# `paths` 규칙은 파일을 Read할 때 뜬다. 새 파일 Write는 사전 Read를 요구하지 않으므로
# 그 경로로 만들면 규칙이 원리적으로 오지 않는다. 침묵으로 돌아가면 그 구멍이 다시 열린다.
check 발화 "새 훅을 Write 로 만듦"               ".claude/hooks/new-hook.sh" write
check 발화 "새 규칙 파일을 Write 로 만듦"         ".claude/rules/new-rule.md" write
check 발화 "CLAUDE.md 편집"                     "CLAUDE.md" write
check 발화 "전역 가드 사본 편집"                  "$HOME/.claude/hooks/guard-secrets.sh" write
check 침묵 "규칙이 없는 경로"                     "docs/adr/README.md" write

echo "── 거울 드리프트 (check-doc-staleness §5) ──"
#
# 저장소를 건드리지 않는다. 거울 파일만 임시 트리에 복사해 거기서 비튼다 — 손으로 비틀고
# 되돌리는 검증은 되돌리기를 잊고, 그러면 다음 세션이 갈라진 사본을 정본으로 믿는다.
# $1 기대(발화/침묵), $2 설명, $3 비틀 파일(없으면 원본 그대로), $4 perl 치환식
check_mirror() {
  local want="$1" desc="$2" target="${3:-}" expr="${4:-}" tmp f got
  tmp=$(mktemp -d) || { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s (임시 디렉터리 실패)\n' "$desc"; return; }
  for f in .claude/rules/data-pipeline.md .claude/rules/test.md .claude/skills/batch-load/SKILL.md; do
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

echo
printf '통과 %d / 실패 %d\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
