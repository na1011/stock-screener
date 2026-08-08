#!/bin/bash
# Bash 훅 공용: tool_input.command 에서 **실행될 텍스트**만 남긴다.
#
# 왜 공용인가 — Bash 훅의 입력에는 두 종류의 텍스트가 섞여 있다. 실행될 명령과, 명령을
# **인용한 산문**(heredoc 본문, 여러 줄 따옴표 인자)이다. 이 분리를 훅마다 따로 하면
# **가드와 알림이 "무엇이 명령인가"에 서로 다른 답을 갖게 된다** — 그것이 가장 나쁘다.
#
# 2026-08-09 실측으로 네 곳이 같은 병을 앓고 있었다:
#   - guard-destructive §1(deny): 커밋 메시지에 `rm -rf`를 **문서로** 적으면 커밋이 막혔다.
#     `ask`가 아니라 **하드 차단**이었고, 에이전트의 가장 쉬운 우회는 그 줄을 메시지에서
#     지우는 것이다 — 하네스가 자기가 지키려던 습관을 스스로 깎는다.
#   - guard-destructive §2(ask): `.env` 언급 + 아무 곳의 `| tail`이면 확인을 요구했다.
#   - warn-bootstrap-args: 적재 명령을 문서화한 산문에 발화했다.
#   - remind-rule-on-shell: 첫 `<<`부터 잘라내 heredoc **뒤**의 실제 읽기를 놓쳤다.
#
# 이 파일이 없으면 source가 실패하고 `set -e`에서 훅이 죽는다. 죽은 훅은 Bash 호출마다
# stderr를 내므로 **침묵이 아니라 시끄럽다.** 세션 시작 부재 경보는 check-doc-staleness.sh에
# 있다(jq 부재를 중앙에서 한 번 알리는 것과 같은 구조다).

# heredoc 본문을 **조건부로** 비운다.
#
# IMPORTANT — 무조건 지우면 새 구멍이 생긴다. `bash <<EOF / rm -rf /tmp/x / EOF`는 본문이
# **실행되고**, 지금 deny로 잡힌다(실측). 그래서 **소비자의 argv0이 아래 허용목록에 있을
# 때만** 본문을 비우고, 나머지(`bash`·`sh`·`psql`·`python`…)는 그대로 남긴다.
#
# 실패 방향이 안전한 쪽이다 — 모르는 소비자는 과탐지로 남고(현 상태 유지) 과소탐지는 늘지
# 않는다. 열거가 여기서 정당한 이유가 그것이다: 빠뜨린 소비자는 무해한 오탐 하나가 되고,
# 반대로 "실행하는 소비자"를 열거했다가 빠뜨리면 침묵이 된다.
#
# **절단이 아니라 본문 제거다.** 첫 `<<`부터 끝까지 버리면
# `git commit <<MSG … MSG ; rm -rf x`를 놓친다(지금 deny로 잡힌다).
#
# 본문 안에서는 새 heredoc을 찾지 않는다. 찾으면, 문서화된 heredoc 명령이 든 산문에서
# 종료 표시를 영원히 못 만나 **나머지 전체가 사라진다** — 가드에서는 그것이 침묵이다.
strip_heredoc_bodies() {
  printf '%s\n' "$1" | awk '
    function argv0_of_prefix(s,   n, parts, seg, i, m, tok, toks, j) {
      # `<<` 앞부분의 마지막 명령 세그먼트에서 argv0을 뽑는다.
      gsub(/&&/, ";", s); gsub(/\|\|/, ";", s); gsub(/\|/, ";", s)
      n = split(s, parts, ";")
      seg = parts[n]
      m = split(seg, toks, /[ \t]+/)
      for (j = 1; j <= m; j++) {
        tok = toks[j]
        if (tok == "") continue
        if (tok ~ /=/) continue
        if (tok ~ /^-/) continue
        if (tok == "sudo" || tok == "env" || tok == "command" || tok == "nohup" || tok == "time") continue
        sub(/^.*\//, "", tok)   # basename
        return tok
      }
      return ""
    }
    in_body {
      # 종료 표시만 본다. `<<-`는 앞쪽 탭을 허용한다.
      line = $0
      if (dash) { t = line; sub(/^[ \t]+/, "", t) } else { t = line }
      if (t == term) { in_body = 0; print; next }
      if (!blank) print
      next
    }
    {
      line = $0
      if (match(line, /<<-?[ \t]*("[A-Za-z_][A-Za-z0-9_]*"|'"'"'[A-Za-z_][A-Za-z0-9_]*'"'"'|[A-Za-z_][A-Za-z0-9_]*)/)) {
        marker = substr(line, RSTART, RLENGTH)
        prefix = substr(line, 1, RSTART - 1)
        dash = (marker ~ /^<<-/) ? 1 : 0
        term = marker
        sub(/^<<-?[ \t]*/, "", term)
        gsub(/["'"'"']/, "", term)
        consumer = argv0_of_prefix(prefix)
        # 본문을 비울 소비자만 열거한다. 그 밖은 전부 남긴다.
        blank = (consumer == "git" || consumer == "cat" || consumer == "tee") ? 1 : 0
        in_body = 1
        print line
        next
      }
      print
    }
  '
}

# 소비자의 argv0이 $2 정규식에 맞는 heredoc의 **본문만** 뽑아 출력한다.
#
# 왜 필요한가 — `psql -d x <<SQL / drop table y; / SQL`은 본문이 실행되는데도 지금 통과한다.
# §1의 SQL 판정이 `-c "…"` 인자만 보고, 세그먼트 분해가 본문을 별도 줄로 쪼개면 그 줄의
# argv0은 `drop`이라 DB 클라이언트로 인식되지 않기 때문이다(2026-08-09 실측).
#
# 본문을 비우는 위 함수와 파서가 겹치지만 한 파일 안의 중복이라 어긋나면 바로 보인다.
# 모드 인자로 합치지 않는 이유는 그쪽이 더 헷갈리기 때문이다 — 하나는 지우고 하나는 뽑는다.
heredoc_bodies_of_consumers() {
  printf '%s\n' "$1" | awk -v want="$2" '
    function argv0_of_prefix(s,   n, parts, seg, m, tok, toks, j) {
      gsub(/&&/, ";", s); gsub(/\|\|/, ";", s); gsub(/\|/, ";", s)
      n = split(s, parts, ";")
      seg = parts[n]
      m = split(seg, toks, /[ \t]+/)
      for (j = 1; j <= m; j++) {
        tok = toks[j]
        if (tok == "") continue
        if (tok ~ /=/) continue
        if (tok ~ /^-/) continue
        if (tok == "sudo" || tok == "env" || tok == "command" || tok == "nohup" || tok == "time") continue
        sub(/^.*\//, "", tok)
        return tok
      }
      return ""
    }
    in_body {
      line = $0
      if (dash) { t = line; sub(/^[ \t]+/, "", t) } else { t = line }
      if (t == term) { in_body = 0; next }
      if (emit) print
      next
    }
    {
      line = $0
      if (match(line, /<<-?[ \t]*("[A-Za-z_][A-Za-z0-9_]*"|'"'"'[A-Za-z_][A-Za-z0-9_]*'"'"'|[A-Za-z_][A-Za-z0-9_]*)/)) {
        marker = substr(line, RSTART, RLENGTH)
        prefix = substr(line, 1, RSTART - 1)
        dash = (marker ~ /^<<-/) ? 1 : 0
        term = marker
        sub(/^<<-?[ \t]*/, "", term)
        gsub(/["'"'"']/, "", term)
        consumer = argv0_of_prefix(prefix)
        emit = (consumer ~ ("^(" want ")$")) ? 1 : 0
        in_body = 1
        next
      }
    }
  '
}

# 따옴표 안을 **전체 문자열 기준**으로 비운다.
#
# 기존 `strip_quotes`는 sed라 줄 단위이고, 그래서 여러 줄 `-m` 메시지의 따옴표를 걷어내지
# 못했다 — 그 안의 `rm -rf` 줄이 명령으로 읽혀 deny가 났다(실측).
#
# 따옴표 문자는 남기고 내용만 버린다(`''`/`""`). 토큰 경계가 유지되고 기존 판정과 모양이 같다.
# 따옴표 안의 개행도 함께 사라지는 것이 의도다 — 인용된 여러 줄은 **토큰 하나**이고,
# 세그먼트 분해가 그것을 여러 명령으로 오해해서는 안 된다.
strip_quotes_global() {
  printf '%s' "$1" | awk '
    { buf = buf $0 "\n" }
    END {
      out = ""; sq = 0; dq = 0
      n = length(buf)
      for (i = 1; i <= n; i++) {
        c = substr(buf, i, 1)
        if (!sq && !dq) {
          if (c == "'"'"'") { sq = 1; out = out c; continue }
          if (c == "\"")    { dq = 1; out = out c; continue }
          out = out c
          continue
        }
        if (sq) {
          if (c == "'"'"'") { sq = 0; out = out c }
          continue
        }
        # dq: 큰따옴표 안에서는 백슬래시가 다음 문자를 감싼다.
        if (c == "\\") { i++; continue }
        if (c == "\"") { dq = 0; out = out c }
      }
      printf "%s", out
    }
  '
}
