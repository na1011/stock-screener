#!/bin/bash
# PreToolUse guard: 파괴적인 Bash 명령을 차단하고, Bash를 경유한 시크릿 읽기는 확인을 요구한다.
# 매칭되지 않으면 exit 0으로 통과시킨다 (안전망 전용, 워크플로우를 막지 않는다).
# 단 그것은 **판정한 뒤**의 이야기다 — 판정 자체를 못 했으면 조용히 나가지 않는다.
#
# 명령의 텍스트가 아니라 실행 구조를 본다. 원시 텍스트 정규식은 "이것이 argv[0]인가",
# "이것이 따옴표 안인가"를 구분할 수 없어 `echo "rm -rf ..."` 같은 단순 언급까지 차단하고
# 정작 `rm -rfd`는 놓친다. 그래서 따옴표를 걷어내고 명령 경계로 쪼갠 뒤 판정한다.
#
# 활성 등록은 `~/.claude/hooks/`의 사본 하나뿐이고, `.claude/hooks/`의 사본은 버전관리·드리프트
# 대조용이며 등록하지 않는다. `~/.claude`는 git도 백업도 없어 전역 사본만 두면 파일이 날아갔을
# 때 되살릴 곳이 없다. 등록 형태도 여기 적는다 — 파일과 등록이 같은 자리에 있으면 갈릴 수 없다.
# `~/.claude/settings.json`의 `hooks`에:
#
#   "PreToolUse": [ { "matcher": "Bash",
#     "hooks": [ { "type": "command", "command": "$HOME/.claude/hooks/guard-destructive.sh" } ] } ]
set -euo pipefail

# jq가 없으면 판정할 수 없다. 안전망이 워크플로우를 막지 않도록 조용히 통과시킨다.
command -v jq >/dev/null 2>&1 || exit 0

# 공용 텍스트 정리 — heredoc 본문과 여러 줄 따옴표를 명령에서 걷어낸다.
# 이것이 없으면 **커밋 메시지에 파괴 명령을 문서로 적기만 해도 deny가 났다**(2026-08-09 실측).
# 기존 헬퍼들은 옮기지 않고 여기 남겼다 — 도는 것을 건드릴 이유가 없고, 이 파일의 판정 로직이
# 가장 위험한 코드다. 공용으로 뺀 것은 새로 만든 두 함수뿐이다.
# shellcheck source=lib/bash-text.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/bash-text.sh"

input=$(cat)
cmd=$(echo "$input" | jq -r '.tool_input.command // empty')

if [ -z "$cmd" ]; then
  exit 0
fi

emit() {
  jq -n --arg decision "$1" --arg reason "$2" \
    '{
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: $decision,
        permissionDecisionReason: $reason
      }
    }'
  exit 0
}

# --- 명령 분해 ---

# 따옴표 안의 내용을 제거한다. 문자열 리터럴에 등장하는 위험 패턴은 실행되지 않는다.
# SQL 판정만은 따옴표 안을 봐야 하므로(psql -c "drop table ...") 원본을 따로 보관한다.
strip_quotes() {
  echo "$1" | sed "s/'[^']*'/''/g; s/\"[^\"]*\"/\"\"/g"
}

# 주석 제거 — `echo done # rm -rf later` 같은 형태를 걸러낸다.
strip_comment() {
  echo "$1" | sed 's/[[:space:]]#[^"'"'"']*$//'
}

# 명령 경계로 분할한다. 각 세그먼트는 하나의 단순 명령이 된다.
# BSD sed(macOS)는 치환문의 \n 을 개행으로 해석하지 않고 문자 n 으로 넣는다.
# 개행 삽입은 tr 로 처리해 GNU/BSD 양쪽에서 동일하게 동작시킨다.
split_segments() {
  echo "$1" | sed 's/&&/;/g; s/||/;/g' | tr ';|()' '\n\n\n\n' \
    | sed 's/^[[:space:]]*//; s/^\(do\|then\|else\|elif\|fi\|done\|{\|}\)[[:space:]]*//'
}

# 세그먼트에서 실제 실행 프로그램(argv[0])을 뽑는다.
# sudo/env 같은 래퍼와 VAR=x 형태의 환경 변수 접두어를 걷어낸다.
# 한계: `sudo -u postgres psql ...` 처럼 래퍼가 값을 받는 플래그를 쓰면 argv0을 놓친다.
# 여기까지 쫓으면 정규식 군비경쟁이 재개되므로 의도적으로 남겨둔다.
argv0_of() {
  local seg="$1" tok
  for tok in $seg; do
    case "$tok" in
      *=*)                                      continue ;;
      sudo|env|command|nohup|time|xargs|builtin) continue ;;
      # 셸 키워드는 명령이 아니다. `do rm -rf $d` 에서 rm 을 argv0 으로 봐야 한다.
      do|then|else|elif|fi|done|'{'|'}'|'!')    continue ;;
      -*)                                       continue ;;
      *) basename "$tok"; return ;;
    esac
  done
}

# 세그먼트의 단문자 플래그 글자를 모두 모은다.
# `-rf`, `-fr`, `-rfd`, `-r -f`를 모두 잡으면서 `--force-with-lease` 같은 장문자 플래그는
# 글자를 기여하지 않게 해 오탐을 막는다.
short_flags_of() {
  local seg="$1" tok out=""
  for tok in $seg; do
    case "$tok" in
      --*) continue ;;
      -*)  out="${out}${tok#-}" ;;
    esac
  done
  echo "$out"
}

has_long_flag() {
  echo " $1 " | grep -qE "[[:space:]]${2}([[:space:]]|=|$)"
}

# 토큰이 **정확히** 그 문자열인지 본다.
# MUST: 단문자 플래그를 `short_flags_of`로 찾지 않는다. 그 함수는 `-Fc`에서 글자 `F`와 `c`를
# 둘 다 모으므로 `pg_restore -Fc`가 `--clean`으로 오탐된다. 대소문자도 갈려야 한다(`-c`≠`-C`).
has_token() {
  local seg="$1" want="$2" tok
  for tok in $seg; do
    [ "$tok" = "$want" ] && return 0
  done
  return 1
}

# --- 1. 파괴적 명령: deny ---

# 실행될 텍스트만 남긴다. 판정 로직은 아래 그대로이고 **입력만** 바뀐다.
#
# `strip_quotes_global`을 쓰는 이유: 기존 `strip_quotes`는 sed라 줄 단위여서 여러 줄 `-m`
# 메시지의 따옴표를 걷어내지 못했고, 그 안의 파괴 명령 줄이 명령으로 읽혔다.
# 아래 SQL 판정은 따옴표 **안**을 봐야 하므로 거기서는 계속 줄 단위 `strip_quotes`를 쓴다.
executable=$(strip_heredoc_bodies "$cmd")
clean=$(strip_quotes_global "$(strip_comment "$executable")")
is_destructive=false
# ask 후보는 **적어만 두고 여기서 emit하지 않는다.** emit은 exit이라, 파괴 판정이 아직
# 안 끝난 자리에서 부르면 뒤에 올 deny를 가린다 — §1b(SQL)와 heredoc 판정이 이 루프
# 뒤에 있다. `cat .env && git push --force`가 deny인 것이 그 순서 덕이다.
ask_reason=""
raw_segments=$(split_segments "$executable")
clean_segments=$(split_segments "$clean")

while IFS= read -r seg; do
  [ -z "${seg// /}" ] && continue
  prog=$(argv0_of "$seg")
  [ -z "$prog" ] && continue
  flags=$(short_flags_of "$seg")

  case "$prog" in
    rm)
      # -r 과 -f 가 함께 있으면 되돌릴 수 없는 삭제다.
      if { [[ "$flags" == *r* ]] && [[ "$flags" == *f* ]]; } \
         || { has_long_flag "$seg" '--recursive' && has_long_flag "$seg" '--force'; } \
         || { [[ "$flags" == *r* ]] && has_long_flag "$seg" '--force'; } \
         || { has_long_flag "$seg" '--recursive' && [[ "$flags" == *f* ]]; }; then
        is_destructive=true; break
      fi
      ;;
    git)
      # --force-with-lease 는 허용한다. 다른 사람이 push했으면 스스로 실패하므로
      # 공유 히스토리가 어긋나는 위험을 오히려 막는다. 차단하면 맨 --force 를 쓰게 된다.
      if echo "$seg" | grep -qE '(^|[[:space:]])push([[:space:]]|$)'; then
        if has_long_flag "$seg" '--force' || [[ "$flags" == *f* ]]; then
          is_destructive=true; break
        fi
      fi
      if echo "$seg" | grep -qE '(^|[[:space:]])reset([[:space:]]|$)' \
         && has_long_flag "$seg" '--hard'; then
        is_destructive=true; break
      fi
      if echo "$seg" | grep -qE '(^|[[:space:]])clean([[:space:]]|$)' \
         && { [[ "$flags" == *f* ]] || has_long_flag "$seg" '--force'; }; then
        is_destructive=true; break
      fi
      ;;
    dropdb)
      # 덤프가 없으면 되돌릴 수 없고, 이 저장소에 정당한 호출이 하나도 없다.
      is_destructive=true; break
      ;;
    pkill|killall)
      # MUST NOT: `kill`을 여기 넣지 않는다. 이 저장소는 `kill <PID>`를 **처방한다** —
      # `ops/stop.sh`가 그렇게 끄고, warn-load-running.sh·inject-progress.sh·ADR-0043이
      # "PID를 특정해서 끈다"고 지시한다. 막으면 훅이 다른 훅의 처방을 차단한다.
      # 이름으로 찾아 죽이는 쪽만 묻는다 — `pkill -f java`는 운영 앱을 함께 죽인다.
      #
      # 한계: `kill -9 $(pgrep -f java)`는 argv0이 `kill`이라 여기 안 걸린다. 막을 수단이
      # 없으므로 "가드가 있으니 pkill류는 막힌다"고 믿지 않는다.
      [ -z "$ask_reason" ] && ask_reason="프로세스를 이름으로 찾아 죽이려는 명령으로 보입니다. 운영 앱까지 함께 죽을 수 있으니 PID를 특정하는 편이 안전합니다."
      ;;
    pg_restore)
      # MUST: 쓰기 플래그가 있을 때만 묻는다. `pg_restore -l`은 TOC를 읽기만 하고,
      # **ADR-0052의 전제가 그것을 쓴다** — argv0만 보고 걸면 check-premises.sh의
      # `vetoed()`가 그 전제를 영영 "판정 불가"로 만든다(0000-template.md가 그 결과를 적는다).
      #
      # deny가 아니라 ask인 이유: `ops/dump-db.sh`가 복구 처방으로 `pg_restore -d -c`를
      # **출력한다.** deny면 되돌려야 하는 바로 그 순간에 되돌리는 길을 막는다.
      if has_token "$seg" '-d' || has_token "$seg" '-C' || has_token "$seg" '-c' \
         || has_long_flag "$seg" '--dbname' || has_long_flag "$seg" '--create' \
         || has_long_flag "$seg" '--clean'; then
        [ -z "$ask_reason" ] && ask_reason="덤프를 데이터베이스에 복원하려는 명령으로 보입니다. 대상 DB의 현재 내용이 덮이거나 지워질 수 있으니 확인 후 진행하세요."
      fi
      ;;
  esac
done <<< "$clean_segments"

# SQL 은 반대로 따옴표 안을 봐야 한다 — 파괴적 구문은 대개 -c "..." 인자로 전달된다.
# 대신 DB 클라이언트를 실행하는 경우로 한정해 `grep 'drop table'` 같은 언급을 걸러낸다.
db_clients='psql|mysql|mariadb|sqlite3|mongosh|mongo|clickhouse-client'
destructive_sql='\b(drop[[:space:]]+(table|database|schema)|truncate[[:space:]]+table)\b'

# 선검사 — 어느 세그먼트에도 파괴 SQL이 없으면 루프를 통째로 건너뛴다.
#
# 왜 필요한가 — 이 루프는 세그먼트마다 서브셸 둘과 `sed`·`basename` 프로세스를 낸다.
# 전제 SQL은 `||`와 괄호가 많아 `split_segments`가 잘게 쪼개므로(65개 전제 = 2,519 세그먼트)
# ADR 전제 검사 훅의 wall 23.4초 중 **17.8초가 여기서 났다**(실측). 그 비용은
# `check-premises.sh`의 예산 검사보다 **먼저** 발생해 예산의 보호를 받지 않는다.
#
# MUST: 선검사 대상은 `$raw_segments`다. `$cmd`·`$executable`·`$clean` 중 어느 것도 아니다.
#   - `grep`이 줄 단위이고 각 세그먼트가 정확히 한 줄이므로, 이것은 아래 루프가 세그먼트마다
#     돌리는 `grep`의 **정확한 OR이다 — 근사가 아니라 항등**이다.
#   - 전체 문자열에 걸면 판정이 **뒤집힌다.** `tr ';|()'`가 전체 문자열에 없던 **줄머리를
#     만들기** 때문이다. `KEY=$(secrets/cat api.key)`가 그 예다(§2의 `^` 앵커에 걸린다).
#   - `$clean`은 더 나쁘다 — 따옴표 안이 비워져 `-c "..."` 인자의 SQL이 통째로 사라진다.
# MUST NOT: `[[ =~ ]]`로 바꾸지 않는다. bash 정규식은 개행을 `[[:space:]]`로 먹고 `^`/`$`가
# 문자열 앵커가 되어 판정 경계가 통째로 달라진다.
if [ "$is_destructive" = false ] && grep -qiE "$destructive_sql" <<< "$raw_segments"; then
  while IFS= read -r seg; do
    [ -z "${seg// /}" ] && continue
    prog=$(argv0_of "$(strip_quotes "$seg")")
    case "$prog" in
      psql|mysql|mariadb|sqlite3|mongosh|mongo|clickhouse-client)
        if echo "$seg" | grep -qiE "$destructive_sql"; then
          is_destructive=true; break
        fi
        ;;
    esac
  done <<< "$raw_segments"
fi

# `psql -d x <<SQL / drop table y; / SQL` 형태. 위 루프는 잡지 못한다 — 세그먼트 분해가 본문을
# 별도 줄로 쪼개면 그 줄의 argv0은 `drop`이라 DB 클라이언트가 아니게 된다. 그래서 소비자가
# DB 클라이언트인 heredoc의 본문만 따로 뽑아 같은 정규식으로 본다.
# 커밋 메시지에 SQL을 **문서로** 적는 경우는 소비자가 `git`이라 여기 걸리지 않는다.
if [ "$is_destructive" = false ]; then
  db_heredoc=$(heredoc_bodies_of_consumers "$cmd" "$db_clients")
  if [ -n "$db_heredoc" ] && echo "$db_heredoc" | grep -qiE "$destructive_sql"; then
    is_destructive=true
  fi
fi

if [ "$is_destructive" = true ]; then
  emit "deny" "파괴적 명령으로 감지되었습니다. 확인 없이 실행하지 마세요."
fi

# 파괴 판정이 전부 끝난 뒤에 ask를 낸다. 위 §1 루프에서 미뤄 둔 것이다.
# MUST: 이 자리를 §2(시크릿) **앞**에 둔다. §2는 선검사에서 `exit 0`으로 빠져나가므로
# 뒤에 두면 시크릿 경로가 없는 명령에서 이 절이 통째로 도달하지 않는다.
if [ -n "$ask_reason" ]; then
  emit "ask" "$ask_reason"
fi

# --- 2. Bash 경유 시크릿 읽기: ask ---
# 파일 접근 hook은 Read/Edit/Write만 검사하므로 `cat .env` 같은 우회 경로가 남는다.
# 정상적인 조회일 수 있으므로 차단(deny)이 아닌 확인(ask)으로 둔다.
#
# §1과 **같은 방식으로** 판정한다. 이 파일 머리 주석이 "원시 텍스트 정규식은 argv[0]인지
# 따옴표 안인지 구분할 수 없어 단순 언급까지 차단한다"고 적어 두고도, 이 절만 정확히 그
# 정규식이었다. 그 결과 `.env`를 **언급**한 커밋 메시지 + 아무 곳의 `| tail`이면 확인을
# 요구했다(2026-08-09 실측). 실제로 다른 세션이 문서 커밋에서 그것을 맞았다.
#
# 따옴표는 걷어내지 않고 본다 — `cat ".env"`처럼 인용된 경로가 진짜 읽기일 수 있고,
# 시크릿 판정에서는 **놓치는 쪽이 한 번 더 묻는 쪽보다 나쁘다.**
secret_paths='(\.env([[:space:]]|$|[^.a-zA-Z])|\.env\.[a-zA-Z]|\.pem|\.p12|\.pfx|\.jks|\.keystore|(^|[[:space:]/])secrets?/|/credentials/|id_rsa|id_ed25519|\.ssh/)'

# 선검사 — 근거는 §1b 위의 주석과 같다. 여기가 파일의 마지막 절이므로 건너뛰는 것과
# `exit 0`이 같은 뜻이고, 그래야 아래 루프의 들여쓰기가 그대로 남는다.
#
# IMPORTANT — 대상이 `$raw_segments`인 것이 이 절에서는 **판정의 문제**다. 위 정규식의
# `(^|[[:space:]/])secrets?/`가 `^` 앵커를 쓰는데, `split_segments`의 `tr ';|()'`가
# 전체 문자열에 없던 줄머리를 만든다. `KEY=$(secrets/cat api.key)`는 전체 문자열에서
# `secrets/` 앞이 `(`라 안 맞고, 세그먼트에서는 줄머리라 맞는다 — 전체 문자열로 선검사하면
# 그 ask가 조용히 사라진다. 회귀표가 이 셋을 잰다.
grep -qE "$secret_paths" <<< "$raw_segments" || exit 0

while IFS= read -r seg; do
  [ -z "${seg// /}" ] && continue
  prog=$(argv0_of "$(strip_quotes "$seg")")
  case "$prog" in
    cat|less|more|head|tail|bat|strings|xxd|od|nl|grep|rg|awk|sed|cp|mv|scp|base64) ;;
    *) continue ;;
  esac

  # 시크릿 경로가 **같은 세그먼트**에 있어야 한다. `git push | tail -3`의 tail은
  # 시크릿과 한 세그먼트에 있지 않다.
  echo "$seg" | grep -qE "$secret_paths" || continue
  # 템플릿·예시 파일만 대상이면 통과시킨다.
  echo "$seg" | grep -qE '\.env\.(example|sample|template|dist)' && continue

  emit "ask" "시크릿 파일을 읽거나 복사하려는 명령으로 보입니다. 실제 시크릿 값이 대화·로그에 남지 않도록 확인 후 진행하세요."
done <<< "$raw_segments"

exit 0
