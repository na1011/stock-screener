#!/bin/bash
# PreToolUse guard: 파괴적인 Bash 명령을 차단하고, Bash를 경유한 시크릿 읽기는 확인을 요구한다.
# 매칭되지 않으면 반드시 exit 0으로 조용히 통과시킨다 (안전망 전용, 워크플로우를 막지 않는다).
#
# 명령의 텍스트가 아니라 실행 구조를 본다. 원시 텍스트 정규식은 "이것이 argv[0]인가",
# "이것이 따옴표 안인가"를 구분할 수 없어 `echo "rm -rf ..."` 같은 단순 언급까지 차단하고
# 정작 `rm -rfd`는 놓친다. 그래서 따옴표를 걷어내고 명령 경계로 쪼갠 뒤 판정한다.
set -euo pipefail

# jq가 없으면 판정할 수 없다. 안전망이 워크플로우를 막지 않도록 조용히 통과시킨다.
command -v jq >/dev/null 2>&1 || exit 0

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

# --- 1. 파괴적 명령: deny ---

clean=$(strip_quotes "$(strip_comment "$cmd")")
is_destructive=false
raw_segments=$(split_segments "$cmd")
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
  esac
done <<< "$clean_segments"

# SQL 은 반대로 따옴표 안을 봐야 한다 — 파괴적 구문은 대개 -c "..." 인자로 전달된다.
# 대신 DB 클라이언트를 실행하는 경우로 한정해 `grep 'drop table'` 같은 언급을 걸러낸다.
if [ "$is_destructive" = false ]; then
  while IFS= read -r seg; do
    [ -z "${seg// /}" ] && continue
    prog=$(argv0_of "$(strip_quotes "$seg")")
    case "$prog" in
      psql|mysql|mariadb|sqlite3|mongosh|mongo|clickhouse-client)
        if echo "$seg" | grep -qiE '\b(drop[[:space:]]+(table|database|schema)|truncate[[:space:]]+table)\b'; then
          is_destructive=true; break
        fi
        ;;
    esac
  done <<< "$raw_segments"
fi

if [ "$is_destructive" = true ]; then
  emit "deny" "파괴적 명령으로 감지되었습니다. 확인 없이 실행하지 마세요."
fi

# --- 2. Bash 경유 시크릿 읽기: ask ---
# 파일 접근 hook은 Read/Edit/Write만 검사하므로 `cat .env` 같은 우회 경로가 남는다.
# 정상적인 조회일 수 있으므로 차단(deny)이 아닌 확인(ask)으로 둔다.
if echo "$cmd" | grep -qE '(^|[[:space:];|&])(cat|less|more|head|tail|bat|strings|xxd|od|nl|grep|rg|awk|sed|cp|mv|scp|base64)([[:space:]]|$)'; then
  if echo "$cmd" | grep -qE '(\.env([[:space:]]|$|[^.a-zA-Z])|\.env\.[a-zA-Z]|\.pem|\.p12|\.pfx|\.jks|\.keystore|/secrets?/|/credentials/|id_rsa|id_ed25519|\.ssh/)'; then
    # 템플릿·예시 파일만 대상이면 통과시킨다.
    if ! echo "$cmd" | grep -qE '\.env\.(example|sample|template|dist)'; then
      emit "ask" "시크릿 파일을 읽거나 복사하려는 명령으로 보입니다. 실제 시크릿 값이 대화·로그에 남지 않도록 확인 후 진행하세요."
    fi
  fi
fi

exit 0
