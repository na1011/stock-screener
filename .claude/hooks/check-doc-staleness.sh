#!/bin/bash
# SessionStart guard: 문서의 시점 의존적 주장과 실제 상태(코드 또는 다른 정본)를 대조해
# 불일치만 보고한다. 루트의 사람용 문서는 대조 대상이 아니다(`.claude/rules/harness-docs.md`).
# 차단하지 않는다. 불일치가 없으면 아무것도 출력하지 않고 조용히 통과한다.
set -euo pipefail

# jq가 없으면 이 검사만 죽는 게 아니라 guard-secrets.sh의 시크릿 판정도 함께 죽는다.
# 그 사실을 먼저 알린다.
#
# 여기서 평문 stdout을 쓰는 이유는 하나뿐이다 — jq에 의존하는 경로로는 jq 부재를 알릴 수 없다.
# SessionStart의 평문 stdout은 그대로 컨텍스트로 들어가므로 jq가 필요 없다.
# 아래 findings 보고가 jq -n을 쓰는 것과 달라 보이지만, 그쪽은 jq가 있을 때만 도달한다.
#
# MUST NOT: 죽는 훅을 이름으로 열거하지 않는다. 그 열거가 곧 낡는 목록이 되고, 낡은 목록은
# "덮여 있다"고 믿게 만든다 — 이 검사가 잡으려는 병과 같은 병이다. 등록 목록의 정본은
# `.claude/settings.json`이다.
if ! command -v jq >/dev/null 2>&1; then
  echo "jq가 없다. jq에 의존하는 훅이 전부 판정을 못 하는 상태다 — 차단 가드와 알림 계층 양쪽이고, 어느 것이 걸리는지는 .claude/settings.json의 등록 목록이 답한다. 특히 .env.production·*.key·credentials.json 같은 경로는 permissions가 덮지 않아 지금 아무것도 막지 않는다. jq를 설치하면 복구된다."
  exit 0
fi

# 이 훅이 중앙 보고자다. 자기 죽음을 알릴 수 있는 것은 자기밖에 없으므로 조용히 나가지 않는다.
root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || {
  echo "문서 낡음 검사를 돌리지 못했다: 프로젝트 루트($root)로 이동할 수 없다. 이 세션에서는 문서-코드 불일치가 보고되지 않는다."
  exit 0
}

findings=""
add() { findings="${findings}- $1"$'\n'; }

# 0. 훅이 source하는 공용 파일이 전부 있는가
#
# 없으면 그것을 source하는 훅이 `set -e`에서 죽는다. 죽은 훅은 Bash 호출마다 stderr를 내므로
# 침묵은 아니지만, **파괴적 명령 차단이 사라진 상태**일 수 있어 세션 시작에 한 번 못 박는다.
# jq 부재를 위에서 중앙 경보로 다루는 것과 같은 이유다.
#
# 이름을 하드코딩하지 않고 실제 `source` 줄에서 읽는다. 목록으로 두면 lib이 늘 때마다
# 따라가야 하고, 따라가지 못한 목록은 "덮여 있다"고 믿게 만든다. 죽는 훅을 **이름으로**
# 세지 않고 개수만 세는 것도 같은 이유다.
#
# MUST NOT: 경로 전체를 한 쌍의 따옴표 안에서 찾지 않는다. `. "$(dirname "${BASH_SOURCE[0]}")/lib/x.sh"`는
# 안쪽에 따옴표가 또 있어서 `"[^"]*/lib/…"` 같은 패턴이 통째로 빗나간다. 실제로 그 형태로
# source하는 훅 셋(**그중 하나가 파괴 명령 차단기다**)을 못 봐서, 사용처를 4가 아니라 1로
# 셌다. 그 형태로만 쓰이는 lib이 생기면 부재가 아예 보고되지 않는다.
# 그래서 **source 줄인지**와 **어떤 lib을 가리키는지**를 따로 본다.
while IFS= read -r libname; do
  [ -z "$libname" ] && continue
  [ -r ".claude/hooks/lib/${libname}" ] && continue
  users=$(grep -rlE "(^|[[:space:]])(\.|source)[[:space:]].*/lib/${libname//./\\.}" \
          .claude/hooks --include='*.sh' 2>/dev/null | grep -c . || true)
  add "\`.claude/hooks/lib/${libname}\`가 없다. 훅 ${users}개가 이것을 source하므로 지금 그만큼이 죽어 있다 — 어느 것인지는 \`.claude/settings.json\`의 등록 목록과 대조해라. 파괴적 명령 차단이나 시크릿 확인이 섞여 있으면 복구 전에는 그 명령들을 직접 검토해야 한다."
done <<< "$(grep -rhE '(^|[[:space:]])(\.|source)[[:space:]]' .claude/hooks --include='*.sh' 2>/dev/null | grep -oE '/lib/[A-Za-z0-9_-]+\.sh' | sed 's|^/lib/||' | sort -u || true)"

# 1. progress.md가 주입 상한을 넘겨 세션 시작 주입이 잘리는가
#
# 이 검사가 없던 동안 실제로 잘렸다. inject-progress.sh는 잘렸다는 사실을 주입문 끝에
# 덧붙이지만, 그 문구 자체가 잘린 문서 뒤에 있어 사람 눈에 잘 띄지 않는다. 결과는
# "뒷부분이 다음 세션에 전달되지 않는데 아무도 모르는" 상태다 — 이 저장소가 가장 경계하는
# 조용한 실패다. 세션 시작에 한 줄로 보고해 그것을 깬다.
#
# 문자 수로 센다. inject-progress.sh가 ${#content}로 자르는데 그것이 바이트가 아니라
# 문자임을 실측 확인했다(8,000자 지점에서 정확히 끊겼다). 바이트로 세면 한글 문서에서
# 상한이 3배 엄격해져 매 세션 오탐이 되고, 오탐이 반복되면 이 검사도 무시된다.
inject_hook=".claude/hooks/inject-progress.sh"
if [ -f docs/progress.md ] && [ -f "$inject_hook" ]; then
  # 상한을 이 파일에 복제하지 않고 주입 훅에서 읽는다. 두 곳에 적으면 한쪽만 바뀌고,
  # 그때 이 검사는 통과하는데 주입은 잘리는 최악의 조합이 된다.
  inject_limit=$(grep -oE '^limit=[0-9]+' "$inject_hook" 2>/dev/null | head -1 | cut -d= -f2)
  if [ -n "${inject_limit:-}" ]; then
    # 경고선을 절단선의 절반에 둔다. 둘이 같은 지점이면 이미 손상된 뒤에야 울린다 —
    # 그때는 뒷부분이 이번 세션에 전달되지 않은 상태이고, 무엇이 잘렸는지도 모른다.
    # 별도 상수를 두지 않고 절단선에서 계산해, 한쪽만 바뀌는 조합을 애초에 만들지 않는다.
    warn_limit=$(( inject_limit / 2 ))
    file_len=$(wc -m < docs/progress.md 2>/dev/null | tr -d ' ')

    # 주입 훅이 백로그 절을 빼고 주입하므로 **파일 크기와 주입 크기가 다르다.** 파서를
    # 여기 복제하지 않고 같은 lib을 쓴다 — 두 곳에 적으면 갈리고, 그때 이 검사는 통과하는데
    # 주입은 다른 것을 낸다. lib이 없거나 절을 못 찾으면 훅이 전부 주입하므로 여기서도
    # 파일 전체가 주입분이다.
    inj_len="$file_len"
    if [ -r .claude/hooks/lib/progress-section.sh ]; then
      # shellcheck source=lib/progress-section.sh
      . .claude/hooks/lib/progress-section.sh
      if progress_has_section docs/progress.md; then
        inj_len=$(progress_kept docs/progress.md | wc -m | tr -d ' ')
      fi
    fi

    if [ -n "$inj_len" ] && [ "$inj_len" -gt "$inject_limit" ]; then
      add "docs/progress.md의 **주입분이 ${inj_len}자로 주입 상한(${inject_limit}자)을 넘는다.** 세션 시작 주입이 이미 잘렸고, 뒷부분은 이번 세션에 전달되지 않았다. 파일을 직접 읽고 줄여라."
    elif [ -n "$inj_len" ] && [ "$inj_len" -gt "$warn_limit" ]; then
      add "docs/progress.md의 **주입분이 ${inj_len}자로 경고선(${warn_limit}자)을 넘었다.** 절단은 ${inject_limit}자부터다. 줄을 더하기 전에 뺄 것을 먼저 정하라 — 고정 예산이지 로그가 아니다. 파일별 함정은 \`.claude/rules/\`로, 작업 절차는 skill로 내린다."
    fi

    # 파일 전체를 따로 잰다. **주입분만 재면 백로그가 무한히 커져도 아무도 안 운다** —
    # 저장소에서 이 파일의 크기를 재는 곳은 여기 하나뿐이다(§1c의 대상 목록에도 없다).
    # 선을 주입분과 같이 쓰는 근거는 임의가 아니다: 절 제목이 바뀌면 필터가 풀려 **전부**
    # 주입되므로, 그때 넘을 값이 곧 파일 크기다.
    if [ -n "$file_len" ] && [ "$file_len" != "$inj_len" ] && [ "$file_len" -gt "$warn_limit" ]; then
      add "docs/progress.md가 ${file_len}자다 — 주입되는 것은 ${inj_len}자이고 나머지는 주입에서 뺀 백로그다. **절 제목이 바뀌면 필터가 풀려 전부 주입되고 그때 경고선(${warn_limit}자)을 넘는다.** 백로그도 고정 예산이다."
    fi
  fi
fi

# 1b. 훅에 문법 오류가 있는가
#
# `PreToolUse` 훅의 종료 코드 2는 **도구 호출을 차단한다.** bash 문법 오류의 종료 코드가
# 정확히 2라, 알림 훅의 오타 하나가 모든 편집을 막는다 — fail-open 규약이 통째로 뒤집히는
# 유일한 경로다. 그 상태는 "왜 갑자기 아무것도 안 되지"로만 보이고 원인이 드러나지 않는다.
if command -v bash >/dev/null 2>&1; then
  broken=""
  while IFS= read -r h; do
    [ -z "$h" ] && continue
    bash -n "$h" 2>/dev/null || broken="${broken}$(basename "$h") "
  done <<< "$(find .claude/hooks -name '*.sh' -type f 2>/dev/null)"
  if [ -n "$broken" ]; then
    add "문법 오류가 있는 훅이 있다: ${broken}— \`PreToolUse\`에 걸려 있으면 종료 코드 2가 **도구 호출을 차단한다.** \`bash -n\`으로 확인하고 고쳐라."
  fi
fi

# 1c. 문서가 줄기만 하고 준 적이 없는가
#
# 절대 예산선을 쓰지 않는다 — 규칙 주입의 크기 상한이 측정된 적이 없어 어떤 숫자도 근거가
# 없고, 근거 없는 경고선은 오탐이 되며 오탐이 반복되면 이 검사 자체가 무시된다.
# 대신 실제로 일어난 병의 형태를 그대로 잡는다: 넷으로 갈리기 전의 단일 하네스 규칙 파일이
# 11커밋 내내 순증가였고 감소 커밋이 0회였다. 그동안 아무도 그것을 알리지 않았다.
#
# 대상이 규칙 파일만이 아닌 이유는 그 병이 규칙 파일 고유가 아니기 때문이다. ADR·api-spec은
# `paths` 규칙이 하나도 덮지 않아 작성 규율이 도달하지 않았고, 정정 층이 실제로 거기 가장
# 두껍게 쌓였다. **낱말("정정"·"틀렸다")을 세는 쪽으로 가지 않는다** — `정정`은 이 도메인의
# 용어이고(정정공시), 남겨야 할 함정 문장이 같은 낱말을 쓴다. 추세는 그 둘을 가르지 않아도 된다.
#
# 줄이 아니라 **문자**로 센다. 한글 산문은 줄 수가 유지된 채 문장만 줄어드는 편집이 흔해
# `--numstat`의 줄 기준으로는 감소가 보이지 않는다(§1이 `wc -m`을 쓰는 것과 같은 이유).
# 파일이 생긴 커밋은 제외한다 — 안 하면 새 규칙 파일이 첫날부터 울린다.
#
# MUST NOT: 문자를 바이트로 바꾸지 않는다. `git cat-file --batch-check`가 크기를 공짜로 주어
# 유혹이 있는데, 한글은 문자당 3바이트라 아래 임계 800·3000이 **조용히 3배 느슨해진다.**
# `lib/regression.sh`의 「한글 문서를 바이트로 세면 안 된다」 행이 그 회귀를 붙든다.
#
# 조회는 **한 번에** 한다. 예전에는 파일마다 `git log`를 두 번(창 12 + 전체) 부르고 리비전마다
# `git show | wc -m`을 불러 프로세스가 538개였고 그것만으로 4.9초였다. 판정 로직은 그대로이고
# 무엇을 몇 번 묻는지만 바뀌었다.
if command -v git >/dev/null 2>&1 \
   && git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
   && [ -d .claude/rules ]; then
  window=12
  min_points=4      # 이보다 이력이 짧으면 추세라고 부를 수 없다
  # 임계가 둘인 이유는 두 부류의 정상이 다르기 때문이다. 규칙 파일은 **고정 예산이 설계**라
  # 순증가 자체가 이상 신호다. ADR·api-spec·skill은 알아낸 것이 늘면 커지는 것이 정상이라
  # 같은 선을 쓰면 정상 성장이 매 세션 울리고, 그러면 이 검사가 통째로 무시된다.
  #
  # 3,000은 유도한 값이 아니라 지금 이력에 맞춘 값이다. 이 창의 분포에서 "지식이 늘어난 것"
  # (830~2,994자)과 "층이 쌓인 것"(4,167·13,412·25,631자) 사이가 거기서 갈렸다.
  # **다시 재지 않고 옮기지 않는다** — 근거가 분포뿐이라 분포가 바뀌면 근거도 바뀐다.
  min_growth=800
  min_growth_doc=3000

  # 대상 목록. **이 순서가 보고 순서의 정본이다** — awk의 `for (k in arr)`는 해시 순서라
  # 그대로 쓰면 보고 문장의 파일 차례가 뒤섞인다(현행은 이 글롭 순서로 낸다).
  targets=""
  for rule in .claude/rules/*.md .claude/skills/*/SKILL.md \
              docs/adr/ADR-*.md docs/api-spec/*.md; do
    if [ -f "$rule" ]; then targets="${targets}${rule}"$'\n'; fi
  done

  if [ -n "$targets" ]; then
    # ① 파일별 rev 목록을 **한 번의 `git log`**로 얻는다.
    #
    # MUST: `core.quotePath=false`를 준다. git은 비ASCII 경로를 `"docs/…/\355\225\234.md"`로
    # 인용해 내는데, 그러면 아래 awk의 키가 셸이 낸 경로와 안 맞아 **그 파일이 조용히 빠진다.**
    # 이 저장소에 한글 이름 문서가 이미 있다.
    # MUST: `--diff-merges=first-parent`를 준다. 머지 커밋의 diff는 기본으로 나오지 않아
    # 그 커밋에서 일어난 증감이 안 보인다. 지금 머지가 0개인 것은 사실이지 불변식이 아니고,
    # 어긋나면 `shrank` 판정이 조용히 뒤집힌다.
    # pathspec을 주지 않고 awk로 거른다 — 92개 글롭을 인자로 넘기면 매칭 0인 글롭이 리터럴로
    # 남아 git이 전체를 거부하고, 그러면 이 검사가 통째로 침묵한다.
    #
    # MUST: `|| true`로 받는다. `set -e`+`pipefail` 아래에서 커밋이 없는 저장소의 `git log`는
    # 128을 내고 **스크립트가 그 자리에서 죽어 §2~§6이 통째로 사라진다.** 회귀표의 임시 트리가
    # 실제로 그 상태다(커밋 없이 `git add`만 한다).
    plan=$(git -c core.quotePath=false log --diff-merges=first-parent \
             --format=$'\001%H' --name-only 2>/dev/null \
           | awk -v W="$window" -v MP="$min_points" '
               NR==FNR { want[$0] = 1; next }
               /^\001/ { rev = substr($0, 2); next }
               NF && ($0 in want) {
                 cnt[$0]++
                 if (cnt[$0] <= W) revs[$0] = revs[$0] rev " "
               }
               END {
                 for (f in revs) {
                   n = split(revs[f], R, " ")
                   while (n > 0 && R[n] == "") n--
                   # 창 안에서 생긴 파일이면 생성 커밋(가장 오래된 것)만 뺀다.
                   if (cnt[f] <= W) n--
                   if (n < MP) continue
                   for (i = n; i >= 1; i--) print f "\t" R[i]
                 }
               }' <(printf '%s' "$targets") - || true)

    if [ -n "$plan" ]; then
      # ② 블롭의 문자 수를 **한 번의 `cat-file`**로 얻는다.
      # perl이 `wc -m`과 같은 값을 낸다: UTF-8에서 문자 수 = 바이트 수 − continuation 바이트 수.
      # MUST: `read`를 쓴다. `sysread`는 파이프 버퍼(16,384)에서 끊겨 큰 ADR의 크기가 거기
      # 고정되고, 그러면 크기 시퀀스가 통째로 거짓이 된다.
      counts=$(printf '%s\n' "$plan" | awk -F'\t' '{ print $2 ":" $1 }' \
               | git cat-file --batch 2>/dev/null \
               | perl -e '
                   binmode(STDIN);
                   while (my $h = <STDIN>) {
                     chomp $h;
                     if ($h =~ /missing$/) { print "0\n"; next }
                     my @p = split / /, $h;
                     my $sz = $p[-1];
                     my $buf = "";
                     read(STDIN, $buf, $sz);
                     read(STDIN, my $nl, 1);
                     my $c = () = $buf =~ /[\x80-\xBF]/g;
                     print $sz - $c, "\n";
                   }' || true)

      # 두 목록의 길이가 어긋나면 짝이 밀려 **엉뚱한 크기로 판정한다.** 조용히 넘기지 않는다.
      n_plan=$(printf '%s\n' "$plan" | grep -c . || true)
      n_count=$(printf '%s\n' "$counts" | grep -c . || true)
      if [ "${n_plan:-0}" -ne "${n_count:-0}" ]; then
        add "문서 증가 추세를 재지 못했다(대상 ${n_plan}개 대 측정 ${n_count}개). 이 세션에서는 순증가 문서가 보고되지 않는다."
      else
        growing=$(paste <(printf '%s\n' "$plan") <(printf '%s\n' "$counts") \
          | awk -F'\t' -v G="$min_growth" -v GD="$min_growth_doc" '
              NR==FNR { ord[++k] = $0; next }
              {
                f = $1; c = $3 + 0
                # 블롭이 없는 리비전(삭제 커밋)은 건너뛴다.
                if (c == 0) next
                if (!(f in first)) first[f] = c
                if ((f in prev) && c < prev[f]) shrank[f] = 1
                prev[f] = c; last[f] = c
              }
              END {
                out = ""
                for (i = 1; i <= k; i++) {
                  f = ord[i]
                  if (!(f in first)) continue
                  # 감소한 커밋이 한 번이라도 있으면 조용하다. 늘었다 줄었다 하는 것은 정상이다.
                  if (f in shrank) continue
                  thr = (f ~ /^\.claude\/rules\//) ? G : GD
                  d = last[f] - first[f]
                  if (d < thr) continue
                  # skill은 파일명이 전부 SKILL.md라 basename만으로는 어느 것인지 알 수 없다.
                  base = f; sub(/.*\//, "", base)
                  lab = base
                  if (f ~ /^\.claude\/skills\//) {
                    dir = f; sub(/\/[^\/]*$/, "", dir); sub(/.*\//, "", dir)
                    lab = dir "/" base
                  }
                  out = out lab "(+" d "자) "
                }
                printf "%s", out
              }' <(printf '%s' "$targets") - || true)

        if [ -n "$growing" ]; then
          add "지난 ${window}커밋 동안 줄어든 적이 없는 문서가 있다: ${growing}— 고정 예산이지 로그가 아니다. 다음에 더할 때 뺄 것을 함께 정하고, **사실이 아니라 그것을 알게 된 경위가 쌓인 것은 아닌지** 본다(\`.claude/rules/doc-facts.md\`)."
        fi
      fi
    fi
  fi
fi

# 2. 프론트엔드 부재 주장 vs 실제
if [ -d frontend ] || [ -d src/main/frontend ] || [ -d src/main/resources/static/src ] \
   || [ -n "$(find . -name '*.vue' -not -path './.git/*' -not -path './build/*' -print -quit 2>/dev/null)" ]; then
  # 루트의 사람용 문서는 보지 않는다 — 이 훅의 대조 대상이 아니다(`harness-docs.md`).
  if grep -qsE '프론트엔드 (디렉터리|디렉토리)?( 없|.*아직)' docs/progress.md; then
    add "진행 상태가 프론트엔드 부재를 주장하지만 실제 프론트엔드 파일·디렉터리가 있다."
  fi
fi

# 3. ADR 문서가 필수 필드(applies_to / 검증)를 갖췄는가
# 실제 ADR은 ADR-NNNN- 접두어를 쓰므로 0000-template.md는 자연히 제외된다.
if [ -d docs/adr ]; then
  incomplete=""
  while IFS= read -r adr; do
    [ -z "$adr" ] && continue
    missing=""
    grep -q '^applies_to:' "$adr" || missing="applies_to "
    grep -q '^## 검증'     "$adr" || missing="${missing}검증"
    [ -n "$missing" ] && incomplete="${incomplete}$(basename "$adr")(${missing}) "
  done <<< "$(find docs/adr -maxdepth 1 -name 'ADR-[0-9][0-9][0-9][0-9]-*.md' 2>/dev/null)"
  if [ -n "$incomplete" ]; then
    add "필수 항목이 빠진 ADR이 있다: ${incomplete}— applies_to 없이는 관련 작업에서 로드되지 않고, 검증 절 없이는 준수 여부를 확인할 수 없다."
  fi
fi

# 4. 규칙의 paths와 ADR의 applies_to glob이 실제 파일과 매칭되는가
#
# 매칭되지 않는 glob은 규칙을 **영원히 로드되지 않게** 만들고, 로드되지 않았다는 사실은
# 어디에도 드러나지 않는다. 실제로 frontend.md의 `**/*.js`·`**/*.scss`가 그 상태였다.
#
# **ADR도 같은 성질이다.** `applies_to`가 아무것도 매칭하지 않으면 `remind-adr.sh`의 참조
# 방향이 그 ADR에 대해 영구히 침묵한다 — 위 3번은 필드의 **존재**만 보므로 통과한다.
# 근거 계층이 그 ADR에 대해 죽는 것이고, 죽었다는 신호가 없다.
#
# 파일 단위로 본다 — 어느 glob이든 하나 매칭되면 통과다. glob 단위로 가면
# `application-*.yaml`처럼 오늘 0건이지만 내일 생길 선행 glob에서 첫날부터 오탐이 나고,
# 오탐이 반복되면 이 검사 자체가 무시된다.
#
# `**`를 `*`로 접는다. bash `[[ ]]` 패턴에서 `*`는 `/`를 넘으므로 `src/main/java/**`가
# 의도대로 동작한다. IMPORTANT — **이 접기는 bash 전용이다.** 같은 대조를 zsh에서 손으로
# 흉내내면 `*`가 `/`를 넘지 않아 0건이 나온다(2026-08-09 실측: bash 47건 / zsh 0건). 그 결과를
# 믿고 멀쩡한 glob을 "고치러" 갈 수 있다. 손으로 확인할 때는 `git ls-files '<glob>'`을 쓴다.
if command -v git >/dev/null 2>&1; then
  tracked=$(git ls-files 2>/dev/null || true)
  if [ -n "$tracked" ]; then
    # 첫 인자는 목록 키(`paths:` 또는 `applies_to:`), 나머지는 검사할 파일들.
    # 매칭 실패한 파일 이름을 공백으로 이어 stdout에 낸다.
    collect_orphans() {
      local key="$1"; shift
      local doc globs matched pat f out=""
      for doc in "$@"; do
        [ -f "$doc" ] || continue
        globs=$(awk -v k="^${key}:[[:space:]]*$" '
          $0 ~ k { f = 1; next }
          f && /^[[:space:]]*-[[:space:]]*/ {
            sub(/^[[:space:]]*-[[:space:]]*/, "")
            gsub(/^["'"'"']|["'"'"']$/, "")
            gsub(/\*\*/, "*")
            # frontmatter 종료선 `---`도 목록 항목처럼 보인다. 접힌 뒤 `--`가 되는데
            # 어느 파일과도 매칭되지 않아 무해하지만, 세어서 헷갈릴 이유가 없다.
            if ($0 != "" && $0 != "-" && $0 != "--") print
            next
          }
          f { exit }
        ' "$doc" 2>/dev/null || true)
        # 목록이 없으면 이 검사 대상이 아니다 — 규칙은 매 세션 로드되고,
        # ADR의 필드 부재는 위 3번이 이미 보고한다.
        [ -z "$globs" ] && continue
        matched=false
        while IFS= read -r pat; do
          [ -z "$pat" ] && continue
          while IFS= read -r f; do
            # shellcheck disable=SC2053  # 우변을 따옴표로 감싸면 glob이 아니라 리터럴이 된다
            if [[ "$f" == $pat ]]; then matched=true; break; fi
          done <<< "$tracked"
          [ "$matched" = true ] && break
        done <<< "$globs"
        [ "$matched" = false ] && out="${out}$(basename "$doc") "
      done
      printf '%s' "$out"
    }

    if [ -d .claude/rules ]; then
      orphan_rules=$(collect_orphans paths .claude/rules/*.md)
      if [ -n "$orphan_rules" ]; then
        add "paths glob이 실제 파일과 하나도 매칭되지 않는 규칙이 있다: ${orphan_rules}— 이 규칙은 영원히 로드되지 않는다. \`git ls-files '<glob>'\`으로 glob을 맞춰라."
      fi
    fi

    if [ -d docs/adr ]; then
      orphan_adrs=$(collect_orphans applies_to docs/adr/ADR-[0-9][0-9][0-9][0-9]-*.md)
      if [ -n "$orphan_adrs" ]; then
        # "깨졌다"고 단정하지 않는다. 아직 만들지 않은 패키지를 가리키는 것은 정상일 수 있다
        # (ADR-0010처럼 미룬 결정). 다만 그동안 포인터가 침묵한다는 사실은 알아야 한다.
        add "applies_to가 지금 어떤 파일과도 매칭되지 않는 ADR이 있다: ${orphan_adrs}— 그 코드가 생길 때까지 \`remind-adr.sh\`의 포인터는 이 ADR에 대해 침묵한다. 예정된 경로면 정상이고, 오타·이름 변경이면 지금 고쳐라. 영구히 매칭될 수 없는 glob이면 아무것도 하지 않으므로 지운다."
      fi
    fi
  fi
fi

# 5. 트리거가 서로 닿지 않아 이중화할 수밖에 없는 사실이 어긋났는가
#
# 두 기제가 서로를 발화시키지 못하면 사본을 없앨 수 없다 — 없애면 한쪽 세션이 그 함정을
# 영영 못 본다. 없앨 수 없으면 어긋남을 잡는다(ADR-0020이 백엔드·프론트 거울에 쓴 형태).
#
# 표에 `추출정규식|파일A|파일B|이름` 한 줄을 더하면 등록된다. 줄바꿈 위치가 파일마다 달라
# 줄 단위로는 못 비교하므로 개행을 접고 문장만 뽑아 대조한다.
# 정규식에 역따옴표를 쓰지 않는다 — 큰따옴표 안에서 명령 치환으로 읽힌다. `.`로 대신한다.
# 「반증 각도」 행만 성질이 다르다. 짝이 저장소 밖(auto-memory)이라 **경로에 프로젝트명이 박혀
# 이 기계에서만 살고**, `lib/regression.sh`의 `check_mirror`는 임시 트리에 파일 넷만 복사하므로
# **이 행은 회귀표가 원리적으로 안 덮는다** — 표가 초록이어도 이 행은 안 재본 것이다. memory를
# memory 시스템이 다시 쓰면 사람이 아무것도 안 고쳐도 울 수 있고 **그 빈도는 미측정이다.**
# 짝이 없는 클론에서는 아래 `[ -f ]`가 조용히 건너뛴다(경보가 아니다).
# 짝의 디렉터리를 변수로 둔다. 완전 경로를 리터럴로 적으면 §6의 죽은-참조 검사가 그것을
# 저장소 상대 경로로 읽어 **멀쩡한 참조를 죽은 포인터로 신고한다**(실제로 그랬다).
mem_dir="$HOME/.claude/projects/-Users-na1011-dev-stock-screener/memory"
mirrors="공공데이터포털은 없는 종목[^)]*)|.claude/rules/data-pipeline.md|.claude/skills/batch-load/SKILL.md|공공데이터포털 응답 모양
ADJ_CLOSE.는 봉 하나로.*FK로 정리가 중단된다\.|.claude/rules/data-pipeline.md|.claude/rules/test.md|ADJ_CLOSE FK 함정
MUST NOT: 0건을 휴장으로 단정하지 않는다\.|docs/api-spec/data-go-kr-stock-price.md|.claude/skills/batch-load/SKILL.md|0건과 휴장의 구분
기준과 현황이 같은 곳에 있으면 기준이 현황에 맞춰 조용히 느슨해진다|docs/phase-criteria.md|.claude/rules/phase-gate.md|기준과 현황의 분리
끝난 뒤에 세운 기준은 이미 한 일을 정당화하는 쪽으로 기운다|docs/phase-criteria.md|.claude/hooks/remind-plan-obligations.sh|착수 전 기준 작성
규칙을 어기는 값과 깨지는 경로는 다르다|.claude/rules/harness-docs.md|docs/progress.md|수용 테스트 0단계
각도를 좁히지 않고, 마지막 둘을.*으로 연다\.|.claude/hooks/remind-plan-obligations.sh|${mem_dir}/adversarial-review-before-executing.md|반증 각도
쓰기 창 [0-9]*거래일 중 [0-9]*이 FY2025 공시일 중앙값 앞이다|src/main/java/com/stockscreener/indicator/calculator/GovernanceMath.java|docs/api-spec/dart-governance.md|공시일 계단의 대가
국내 휴장 평일 [0-9]*일 중 미국 개장이 [0-9]*일이다|src/main/java/com/stockscreener/pipeline/collector/PriceCalendar.java|docs/api-spec/alpaca-bars.md|국내 휴장·미국 개장
ARRAY.'ARCC','CBC','OBDC'.|src/main/java/com/stockscreener/pipeline/collector/KnownSourceGaps.java|docs/adr/ADR-0044-sector-axis.md|SIC 결손 허용목록
선별자」다. 하한이 대부분을 통과시키는 것은 하한이 제 일을 하는 모양이다|docs/adr/ADR-0066-lynch-presets-and-read-window.md|docs/adr/ADR-0067-buffett-preset-and-capex.md|하한을 결함으로 읽지 않는 근거"

extract_mirror() {  # $1=파일 $2=정규식
  tr '\n' ' ' < "$1" | tr -s ' ' | sed -n "s/.*\($2\).*/\1/p"
}
while IFS='|' read -r pat fa fb label; do
  [ -z "$pat" ] && continue
  [ -f "$fa" ] && [ -f "$fb" ] || continue
  ma=$(extract_mirror "$fa" "$pat")
  mb=$(extract_mirror "$fb" "$pat")
  if [ -z "$ma" ] || [ -z "$mb" ]; then
    add "${label}의 사본이 한쪽에서 사라졌다(${fa}=$([ -n "$ma" ] && echo 있음 || echo 없음), ${fb}=$([ -n "$mb" ] && echo 있음 || echo 없음)) — 두 트리거가 서로 닿지 않아 한쪽만으로는 그 세션이 이 함정을 못 본다."
  elif [ "$ma" != "$mb" ]; then
    add "${label}이 ${fa}와 ${fb}에서 갈렸다 — 거울은 어긋나도 아무 데서도 티가 나지 않는다. 두 문장을 다시 같게 맞춰라."
  fi
done <<< "$mirrors"

# 5b. phase 완료 판정에 통과 여부가 섞였는가
#
# `docs/phase-criteria.md`는 **기준**만 담고 통과 여부는 `docs/progress.md`가 담는다. 둘이
# 같은 곳에 있으면 **기준이 현황에 맞춰 조용히 느슨해진다** — 그 파일 머리가 스스로 그렇게
# 적어 두었다.
#
# **알림으로는 못 막는 종류다.** 실제 오염 1건(2026-08-10)은 머리의 MUST NOT을 모르고 어긴
# 것이 아니라, `progress.md` 예산에 밀려 **알고 옮긴** 것이었다. 의도적 이동은 사전 알림이
# 아니라 사후 탐지로만 잡힌다. 그래서 여기 둔다.
#
# 패턴은 상태를 **단정하는 서술어**만 잡는다. `달성`·`충족했다`를 넣으면 "달성률"·"충족하므로"
# 같은 **기준 본문**에 걸려 오탐이 나고, 오탐이 반복되면 이 검사 자체가 무시된다.
# `통과했다`만 `([^는]|$)`로 닫는다. `통과했다는 것`은 **상태 단정이 아니라 그 개념을 부르는
# 어형**이고 위 "기준 본문" 부류에 정확히 들어간다 — `phase-criteria.md`가 「대상이 없다는 것과
# 통과했다는 것이 다르다」로 **반대말을 하는데** 이 검사가 그것을 오염으로 신고했다.
#
# **문장이 아니라 패턴을 고친 이유**는 방향이다. 탐지기에 맞춰 정본 문서의 문장을 고치면 다음
# 세션이 같은 문장을 다시 쓰고 같은 자리에서 다시 막힌다.
#
# 이력 13커밋 전수에서 오탐 0·미탐 0을 확인하고 고른 패턴이었고 **당시엔 참이었다** — 오탐
# 문장은 `bc8e490`에서 들어왔고 그 뒤 17커밋 내내 울고 있었다. 좁힌 뒤 `phase-criteria.md`
# **전 리비전 재측정**: 그 17개가 1 → 0이 되고, 진짜 오염 4개(`06daab3`·`5741707`·`cb1db6c`·
# `c0149d9`)는 1을 유지한다. 진짜 오염은 `(미충족|충족)(이고|…)` 가지가 잡아 잃는 것이 없다.
criteria_doc="docs/phase-criteria.md"
if [ -f "$criteria_doc" ]; then
  polluted=$(grep -nE '(미충족|충족)(이고|이다|이었|됐다|되었다)|완료(다|했다|됐다)|통과했다([^는]|$)' \
             "$criteria_doc" 2>/dev/null | cut -d: -f1 | tr '\n' ' ' || true)
  if [ -n "${polluted// /}" ]; then
    add "\`${criteria_doc}\`에 통과 여부로 읽히는 서술이 있다(${polluted}행) — 기준과 현황이 같은 곳에 있으면 **기준이 현황에 맞춰 조용히 느슨해진다.** 현황은 \`docs/progress.md\`, **phase의 예외와 그 경위는 \`docs/adr/\`가 정본이다.** 기준 문서에는 판정 기준만 남기고 나머지는 그리로 보내라."
  fi
fi

# 5c. 적재 잡 이름이 자리마다 갈렸는가
#
# `lib/load-process.sh`가 `MUST: BootstrapRunner의 switch에 잡을 더하면 여기도 함께 더한다`고
# 적어 두는데 **실제로 넷이 빠져 있었다.** 빠지면 몇 시간짜리 적재 위에 다른 적재를 경고 없이
# 띄운다. `guard-rebuild-without-dump.sh`는 더 비싸다 — **차단 가드**라 이름이 갈리면 덤프 없는
# 수정계수 재구축이 조용히 통과한다.
#
# MUST: SKILL.md의 frontmatter와 본문을 **합집합으로 다루지 않는다.** 합치면 "frontmatter만
# 낡은" 상태가 침묵하는데, `description`은 매 세션 상주하는 유일한 부분이라 그쪽이 낡는 것이
# 더 나쁘다.
# MUST: 어느 자리든 **빈 집합이면 발화한다.** 빈 집합을 "대상 없음"으로 넘기면 이 검사가 통째로
# 침묵하고 그 침묵은 "일치한다"와 구분되지 않는다. 실제로 훅 쪽을 `--job=[A-Za-z|-]*`로 뽑으면
# 여는 괄호가 문자군 밖이라 빈 집합이 나온다.
# MUST: SKILL.md 본문은 **코드펜스 안**만 본다. 산문은 아직 없는 잡을 이름으로 부를 수 있다
# (`docs/progress.md`가 예정된 판정 잡을 그렇게 적는다). 펜스 밖까지 보면 그것이 오탐이 된다.
# MUST: runner 쪽은 `switch (job)` 블록 **안**만 본다. 파일에 다른 switch가 생기면 그 case가
# 잡 이름으로 섞인다.
# MUST: `|| true`로 받는다. `src/main/java`가 없는 트리에서 `find`가 1을 내고 `pipefail`이
# 그것을 물려받아, `set -e` 아래에서 **이 대입 한 줄이 훅을 통째로 죽인다** — §6·§6b가
# 사라지고 그 침묵은 "불일치 없음"과 구분되지 않는다. 회귀표의 임시 트리가 실제로 그 상태다.
runner_src=$(find src/main/java -name BootstrapRunner.java -type f 2>/dev/null | head -1 || true)
job_lib=".claude/hooks/lib/load-process.sh"
job_skill=".claude/skills/batch-load/SKILL.md"
job_guard=".claude/hooks/guard-rebuild-without-dump.sh"
if [ -n "${runner_src:-}" ] && [ -f "$job_lib" ] && [ -f "$job_skill" ] && [ -f "$job_guard" ]; then
  jobs_of() {  # $1 라벨, 나머지는 파이프라인이 stdin으로 받는다
    sort -u | tr '\n' ' ' | sed 's/ $//'
  }
  j_runner=$(awk '/switch \(job\)/ { f = 1 }
                  f && /^[[:space:]]*};[[:space:]]*$/ { f = 0 }
                  f && match($0, /case "[a-z][a-z0-9-]*"/) { print substr($0, RSTART + 6, RLENGTH - 7) }' \
             "$runner_src" 2>/dev/null | jobs_of)
  j_hook=$(grep -oE -- '--job=\([a-z0-9|-]+\)' "$job_lib" 2>/dev/null \
           | sed 's/--job=(//; s/)//' | tr '|' '\n' | jobs_of)
  j_fm=$(awk '/^description:/' "$job_skill" 2>/dev/null | grep -oE -- '--job=[a-z0-9|-]+' \
         | sed 's/--job=//' | tr '|' '\n' | jobs_of)
  j_body=$(awk '/^```/ { f = !f; next } f' "$job_skill" 2>/dev/null \
           | grep -oE -- '--job=[a-z0-9-]+' | sed 's/--job=//' | jobs_of)
  j_guard=$(grep -oE -- '--job=[a-z0-9-]+' "$job_guard" 2>/dev/null | sed 's/--job=//' | jobs_of)

  job_empty=""
  [ -z "$j_runner" ] && job_empty="${job_empty}BootstrapRunner.java "
  [ -z "$j_hook" ]   && job_empty="${job_empty}lib/load-process.sh "
  [ -z "$j_fm" ]     && job_empty="${job_empty}batch-load/SKILL.md(frontmatter) "
  [ -z "$j_body" ]   && job_empty="${job_empty}batch-load/SKILL.md(본문) "
  [ -z "$j_guard" ]  && job_empty="${job_empty}guard-rebuild-without-dump.sh "
  if [ -n "$job_empty" ]; then
    add "적재 잡 이름을 하나도 못 뽑은 자리가 있다: ${job_empty}— **추출이 깨진 것이지 잡이 없는 것이 아니다.** 이 상태로는 자리 사이의 어긋남이 통째로 침묵한다."
  else
    job_diff=""
    [ "$j_hook" != "$j_runner" ]  && job_diff="${job_diff}lib/load-process.sh "
    [ "$j_fm" != "$j_runner" ]    && job_diff="${job_diff}batch-load/SKILL.md(frontmatter) "
    [ "$j_body" != "$j_runner" ]  && job_diff="${job_diff}batch-load/SKILL.md(본문) "
    if [ -n "$job_diff" ]; then
      add "적재 잡 이름이 \`BootstrapRunner\`의 \`switch\`와 갈렸다: ${job_diff}— 정본은 runner다(\`${j_runner}\`). 훅이 갈리면 적재가 도는 것을 못 보고, skill이 갈리면 있는 잡을 못 부르거나 없는 잡을 부른다."
    fi
    # 이 자리만 **부분집합**이다. 전량이 아니라 재구축·재무 둘만 막는 것이 그 가드의 설계다.
    job_orphan=""
    for j in $j_guard; do
      case " $j_runner " in *" $j "*) ;; *) job_orphan="${job_orphan}${j} " ;; esac
    done
    if [ -n "$job_orphan" ]; then
      add "\`guard-rebuild-without-dump.sh\`가 \`BootstrapRunner\`에 없는 잡 이름을 막고 있다: ${job_orphan}— **차단 가드라 어긋나면 증상이 없다.** 이름이 바뀌었으면 덤프 없는 재구축이 지금 그냥 통과한다."
    fi
  fi
fi

# 5d. 착수 전 사전등록한 절대 임계가 코드와 갈렸는가
#
# `docs/phase-criteria.md`의 P14 표는 **코드를 한 줄도 만들기 전에** 적은 값이고, `PresetCatalog`가
# 그것을 실제로 들고 있다. 둘이 갈려도 아무도 모른다 — 코드에서 값을 바꾸면 등록 표는 그대로
# 남고, 그러면 **사전등록이 사후정당화로 조용히 바뀐다.** `phase-gate.md`가 막으려는 그 상태다.
#
# MUST: **표를 자동 파싱하지 않고 아래를 열거한다.** 등록 표는 끝난 phase의 기록이라 지우지
# 않으므로(`phase-gate.md`), 나중 결정이 **정당하게 갈아엎은 행**이 섞여 있다. 실측으로 넷이다 —
# `DISPARITY_200`·`BETA_104W ≤ 1.2`(스윙 프리셋 폐기, ADR-0074) · `ALTMAN_Z`(같은 커밋에서
# 소비자를 잃었고 지표는 남겼다) · `EPS_GROWTH_TTM_YOY ≥ 15/25`(`QVG_KR` 폐기, 커밋 `ad88d9d`).
# 표 전체를 같게 요구하면 이 넷이 매 세션 오탐이 되고, 오탐이 반복되면 이 검사가 무시된다.
#
# MUST: **값의 끝을 다음 구분자까지 닫는다.** `≤ 0`으로 열어 두면 `≤ 0.10`에 매치해 침묵한다 —
# 그것은 가정이 아니라 ADR-0061 §규칙이 「초안의 발생액 ≤0.10이 96% 통과였다」로 적어 둔
# 되돌림 경로다.
# MUST: **개수를 함께 본다.** `ACCRUAL_RATIO ≤ 0`은 코드 5곳, `ROA ≥ 0`은 2곳이라 「있는가」만
# 물으면 **한 곳만 바뀌어도 남은 곳이 grep을 만족시켜 침묵한다.**
# MUST: **개행을 접고 센다.** 값이 다음 줄에 있는 호출이 실재한다(`MARKET_CAP`·`TURNOVER_60`).
# MUST: **`|| true`로 받는다.** 매치 0은 이 검사가 잡으려는 바로 그 상태인데, `set -e` 아래에서
# 대입으로 받으면 그 순간 훅이 죽고 §6·§6b가 통째로 사라진다.
#
# **사정거리** — 이 검사가 보는 것은 아홉이다. `PresetCatalog`의 절대 임계 리터럴은 72개이고,
# P15 이후의 등록 표는 열 구성이 달라 여기서 안 본다. **새 조건을 등록 표에 넣게 만드는 기제는
# 없다** — 그 MUST는 `phase-criteria.md` 본문에 있어 그 파일을 열어야만 뜬다.
#
# 한 줄이 `자바계산기|연산자|코드값|기대개수|표코드정규식|표기호|표값`이다.
# MUST: 표코드정규식에 `|`를 쓰지 않는다. 구분자와 같아 **필드가 조용히 쪼개지고**, 그러면
# 그 행이 늘 「등록 표에서 사라졌다」로 오발화한다(실제로 `[^|]*`를 넣었다가 그렇게 됐다).
#
# 연산자와 값 **사이의 인자 하나를 허용한다**(`([A-Z_]+, )?`). 임계에 시장 축이 붙으면서
# `requiredIn(..., ComparisonOperator.GTE, KRW, "1000000000000")` 모양이 생겼는데(ADR-0106),
# 이 검사가 재는 것은 **값**이지 호출 모양이 아니다. 안 넓히면 값이 그대로인데도
# 「코드 0곳」으로 매 세션 거짓 발화한다 — 거짓 발견은 침묵보다 나쁘다.
# 표에서 같은 지표가 두 번 나오면(BETA_104W가 저변동 축·스윙 게이트 둘이다) 괄호 주석으로
# 가른다 — 아래 정규식의 `.`은 역따옴표다.
preset_reg="AtrPct14|LTE|5.9|1|ATR_PCT_14|≤|5.9
Beta104w|LTE|0.40|1|BETA_104W. (저변동|≤|0.40
VolumeRatio20|GTE|1.5|1|VOLUME_RATIO_20|≥|1.5
Adx14|GTE|20|1|ADX_14|≥|20
Rsi2|LT|10|1|RSI_2|<|10
AccrualRatio|LTE|0|5|ACCRUAL_RATIO|≤|0
Roa|GTE|0|2|ROA|≥|0
MarketCap|GTE|1000000000000|1|MARKET_CAP|≥|1조원
TurnoverRatio60|GTE|0.15|3|TURNOVER_RATIO_60|≥|0.15%"

preset_src=$(find src/main/java -name PresetCatalog.java -type f 2>/dev/null | head -1 || true)
criteria_reg="docs/phase-criteria.md"
if [ -n "${preset_src:-}" ] && [ -f "$criteria_reg" ]; then
  # 개행을 접어 한 줄로 만든다. 호출이 여러 줄에 걸쳐 있어도 같은 정규식으로 잡힌다.
  preset_flat=$(tr '\n' ' ' < "$preset_src" | tr -s ' ' || true)
  criteria_flat=$(tr '\n' ' ' < "$criteria_reg" | tr -s ' ' || true)
  reg_drift=""
  while IFS='|' read -r r_calc r_op r_val r_want r_code r_sym r_tval; do
    [ -z "${r_calc:-}" ] && continue
    got=$(printf '%s' "$preset_flat" \
          | grep -oE "${r_calc}Calculator\.CODE, ComparisonOperator\.${r_op}, ([A-Z_]+, )?\"${r_val}\"" 2>/dev/null \
          | grep -c . || true)
    intable=$(printf '%s' "$criteria_flat" \
              | grep -c "${r_code}[^|]*| ${r_sym} ${r_tval} |" 2>/dev/null || true)
    if [ "${got:-0}" -ne "${r_want:-0}" ]; then
      reg_drift="${reg_drift}${r_code} ${r_sym} ${r_tval}(코드 ${got}곳, 등록은 ${r_want}곳) "
    elif [ "${intable:-0}" -eq 0 ]; then
      reg_drift="${reg_drift}${r_code} ${r_sym} ${r_tval}(코드에는 있는데 등록 표에서 사라졌다) "
    fi
  done <<< "$preset_reg"
  if [ -n "$reg_drift" ]; then
    add "착수 전 등록한 절대 임계가 코드와 갈렸다: ${reg_drift}— 정본은 **등록 표**다(\`docs/phase-criteria.md\` P14, 코드를 만들기 전에 적었다). 코드를 고친 것이면 **푸는 쪽인지 조이는 쪽인지를 함께 적고 사용자에게 올린다**(\`phase-gate.md\`). 등록 표가 지워진 것이면 되살린다 — 끝난 phase의 기준도 지우지 않는다."
  fi
fi

# 6. 산문이 가리키는 하네스 파일이 실제로 있는가
#
# §4와 방향이 반대다. §4는 frontmatter의 glob이 파일을 잡는지 보고(그 파서는 리스트가
# 끝나면 즉시 멈춰 본문을 한 줄도 읽지 않는다), 여기는 **본문이 부르는 이름이 실재하는지**
# 본다. 규칙 파일 하나가 넷으로 갈렸을 때 참조 21곳이 따라가지 못한 채 남았고, 어떤 검사도
# 그것을 보지 않았다.
#
# **완전 경로만 보면 안 된다.** 그 21곳 중 11곳이 역따옴표로 감싼 파일명 단독이었다. 절반만
# 보는 검사는 나머지 절반을 "덮여 있다"고 믿게 만든다 — 이 훅이 잡으려는 병과 같은 병이다.
if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  # 외부 패키지의 표준 파일명. 저장소에 없는 것이 정상이라 이름으로 거른다. 늘어나면
  # 여기 더한다 — 이 목록이 낡으면 오탐이 늘어 눈에 띄므로 조용히 무너지지 않는다.
  external='LICENSE.md|LICENCE.md|CHANGELOG.md|NOTICE.md|CONTRIBUTING.md'

  # 참조가 실재하는지 찾는 디렉터리. **문서를 담는 디렉터리가 늘면 여기 더한다** —
  # 빠뜨리면 멀쩡한 참조가 죽은 포인터로 보고되고, 그 오탐이 반복되면 이 검사 자체가 무시된다.
  # `docs/api-spec`이 실제로 그 상태였다(세 번 연속 오탐).
  resolve_name() {  # 파일명 단독 참조가 어디엔가 있는가
    local d
    for d in .claude/rules .claude/hooks .claude/hooks/lib .claude/skills ops docs docs/adr docs/api-spec docs/external . "$HOME/.claude/projects/-Users-na1011-dev-stock-screener/memory"; do
      [ -e "$d/$1" ] && return 0
    done
    return 1
  }

  dead_refs=""
  note_dead() {
    case " $dead_refs " in *" $1 "*) ;; *) dead_refs="${dead_refs}$1 " ;; esac
  }

  # 회귀표는 제외한다. 픽스처가 new-rule.md 처럼 **존재하지 않는 것이 의미인** 이름을
  # 일부러 쓴다. 알려진 사각이고, 그래서 그 파일의 참조는 이 검사가 보증하지 않는다.
  #
  # 이 검사는 "이름을 부르는 것"과 "예시로 인용하는 것"을 가르지 못한다. 실재하지 않는
  # 이름을 예로 들 때는 **역따옴표를 씌우지 않는다** — 씌우면 여기 걸린다(이 주석이 그렇게
  # 걸렸다). 가짜 이름에 강조가 필요할 만큼 중요하면 그것은 대개 진짜 참조다.
  #
  # 파일마다 grep을 두 번 부르면 133개 × 2 = 266 프로세스가 되고 그것만으로 1.0초였다.
  # 목록을 통째로 넘겨 두 번만 부른다.
  #
  # MUST: `-H`로 파일명을 함께 받아 **파일별 묶음**을 재구성한다. 전역 `sort -u` 하나로
  # 뭉개면 보고 문장의 나열 순서가 「파일 순서 → 파일 안 정렬」에서 **사전순**으로 바뀐다.
  # `git ls-files`가 경로순이라 `sort -u`가 그 두 층을 그대로 재현한다.
  # MUST NOT: `git ls-files -z`에 `grep -v`를 물리지 않는다. BSD grep이 NUL 스트림을
  # 바이너리로 판정해 목록 대신 `Binary file … matches` 한 줄을 내고, 그러면 검사 대상이
  # 0개가 되어 **§6이 통째로 침묵한다** — 그 침묵은 "죽은 참조 없음"과 구분되지 않는다.
  # MUST: `core.quotePath=false`를 준다. `git ls-files`도 비ASCII 경로를 인용해 내고, 그러면
  # 그 이름이 grep에 열리지 않아 **그 문서만 매 세션 조용히 검사에서 빠진다.** 이 저장소의
  # `docs/external/` 한글 문서 셋이 실제로 그 상태였다(133개 중 3개 미검사).
  files=$(git -c core.quotePath=false ls-files '*.md' '*.sh' 2>/dev/null | grep -v '^\.claude/hooks/lib/regression\.sh$' || true)
  if [ -n "$files" ]; then
    hits_path=$(printf '%s\n' "$files" | tr '\n' '\0' \
      | xargs -0 grep -oHE '(\.claude/[A-Za-z0-9_./-]+\.(md|sh)|docs/adr/[A-Za-z0-9_.-]+\.md|ops/[A-Za-z0-9_.-]+\.sh)' 2>/dev/null \
      | sort -u || true)
    hits_name=$(printf '%s\n' "$files" | tr '\n' '\0' \
      | xargs -0 grep -oHE '`[A-Za-z0-9_.-]+\.(md|sh)`' 2>/dev/null \
      | tr -d '`' | sort -u || true)

    # 파일 순서대로, 파일마다 완전경로 참조 다음에 이름 단독 참조를 낸다 — 옛 중첩 루프와
    # 같은 차례다. 첫 열의 `P`/`N`이 어느 쪽인지 알린다.
    #
    # MUST NOT: 이 목록들을 `awk -v`로 넘기지 않는다. **`-v`의 값은 개행을 담지 못한다** —
    # BWK awk가 `newline in string`으로 죽고, 그러면 `ordered`가 비어 §6이 통째로 침묵한다.
    # 죽은 참조가 없는 저장소에서는 그 침묵이 정상 출력과 **똑같아서** 출력 대조로도 안 잡힌다
    # (실제로 그렇게 통과시킨 뒤 회귀표의 순서 행이 잡았다). 태그를 붙여 stdin으로 흘린다.
    ordered=$( { printf '%s\n' "$files"     | sed 's/^/F\t/'
                 printf '%s\n' "$hits_path" | sed 's/^/P\t/'
                 printf '%s\n' "$hits_name" | sed 's/^/N\t/'; } \
      | awk -F'\t' '
          $1 == "F" { if ($2 != "") ord[++k] = $2; next }
          $1 == "P" || $1 == "N" {
            if ($2 == "") next
            j = index($2, ":"); if (j == 0) next
            f = substr($2, 1, j-1); m = substr($2, j+1)
            if ($1 == "P") A[f] = A[f] m "\n"; else B[f] = B[f] m "\n"
            next
          }
          END {
            for (i = 1; i <= k; i++) {
              f = ord[i]
              n = split(A[f], X, "\n"); for (m = 1; m < n; m++) if (X[m] != "") print "P\t" X[m]
              n = split(B[f], Y, "\n"); for (m = 1; m < n; m++) if (Y[m] != "") print "N\t" Y[m]
            }
          }' || true)

    while IFS=$'\t' read -r kind ref; do
      [ -z "${ref:-}" ] && continue
      if [ "$kind" = "P" ]; then
        [ -e "$ref" ] || note_dead "$ref"
      else
        # 외부 표준 파일명 거르기. `grep -qE`를 부르면 참조 하나당 프로세스가 하나 는다 —
        # 파이프로 구분한 목록에 대한 멤버십 검사라 셸만으로 같은 판정이 된다.
        case "|${external}|" in
          *"|${ref}|"*) continue ;;
        esac
        resolve_name "$ref" || note_dead "$ref"
      fi
    done <<< "$ordered"
  fi

  # 6b. 산문이 **이름으로 부르는 절**이 실재하는가
  #
  # §6과 방향이 같고 대상만 다르다. `doc-facts.md`가 「절을 번호로 가리키지 않는다 — 절 제목으로
  # 가리킨다」고 요구하는데, **제목으로 가리켜도 그 절이 사라지면 아무도 울지 않았다.**
  # `104d79b`가 `progress.md`의 한 절을 통째로 지우면서 그 안의 미결 질문이 함께 증발했고,
  # `harness-docs.md`가 바로 그 사고를 금지하는 문장을 가진 채로 그 일이 났다.
  #
  # 사정거리를 정직하게 적는다 — 이 검사는 홑낫표로 감싼 뒤 `절`이 따라오는 형태만 본다.
  # **`§` + 숫자 참조는 원리적으로 못 잡고**, 그것은 `doc-facts.md`가 이미 「어떤 훅도 잡지
  # 않는다」로 적어 둔 자리다. 여기서 그 형태까지 열면 483건이 한꺼번에 대상이 된다.
  #
  # MUST NOT: 실재하지 않는 절 이름을 이 파일에 **예시로 적지 않는다.** §6의 역따옴표 함정과
  # 같다 — 적으면 이 검사가 자기 주석에 걸린다.
  # MUST NOT: 폴더로 대상을 거르지 않는다. `doc-facts.md`가 그 판정 방식을 문장 그대로 기각한다
  # (`docs/external/`에 에이전트 산출물이 섞여 있다). 제외는 회귀표 하나뿐이고 §6과 같은 이유다.
  #
  # 해소는 **어느 추적 마크다운의 제목 줄에라도 그 문자열이 있는가**로 본다. 어느 문서인지까지
  # 묶으면 표를 손으로 유지해야 하고, 잡으려는 병(절이 통째로 사라지는 것)에는 이것으로 충분하다.
  if [ -n "$files" ]; then
    headings=$(printf '%s\n' "$files" | tr '\n' '\0' \
      | xargs -0 grep -hE '^#{1,6}[[:space:]]' 2>/dev/null | sort -u || true)
    sec_refs=$(printf '%s\n' "$files" | tr '\n' '\0' \
      | xargs -0 grep -hoE '「[^」]{2,60}」[[:space:]]*절' 2>/dev/null \
      | sed 's/」[[:space:]]*절$//; s/^「//' | sort -u || true)
    dead_secs=""
    while IFS= read -r sec; do
      [ -z "$sec" ] && continue
      # MUST: 헤어스트링으로 먹인다. `printf … | grep -q`는 **`pipefail` 아래에서 거짓 발화한다** —
      # `grep -q`가 첫 매치에서 즉시 끝나면 아직 쓰고 있던 `printf`가 SIGPIPE로 죽고 파이프라인이
      # 141을 내므로, **실재하는 절이 「사라졌다」로 신고된다.** 게다가 그것이 **매치 위치에
      # 의존한다** — 정렬된 제목 1,547줄에서 앞쪽(326·352·371행)에 걸린 셋만 141이고 뒤쪽
      # (730·1154·1538행)은 0이었다. 즉 같은 코드가 문서가 늘 때마다 다른 절을 신고한다.
      # 오탐이 반복되면 이 검사 자체가 무시되므로 파이프를 쓰지 않는다.
      grep -qF -- "$sec" <<< "$headings" || dead_secs="${dead_secs}「${sec}」 "
    done <<< "$sec_refs"
    if [ -n "$dead_secs" ]; then
      add "산문이 이름으로 부르는데 제목으로 실재하지 않는 절이 있다: ${dead_secs}— 절이 지워졌거나 제목이 바뀐 것이다. **절과 함께 그 안의 내용이 사라졌는지부터 본다** — 실제로 미결 질문 하나가 그렇게 증발한 적이 있다. 이름만 바뀐 것이면 부르는 쪽을 지금 고쳐라."
    fi
  fi

  if [ -n "$dead_refs" ]; then
    add "산문이 가리키는데 실재하지 않는 파일이 있다: ${dead_refs}— 이름이 바뀌거나 갈렸는데 참조가 안 따라간 것이다. \`grep -rn\`으로 부르는 곳을 전부 찾아 지금 파일로 바꿔라. 죽은 포인터는 다음 세션이 \"그 파일에 있다\"고 믿게 만든다."
  fi
fi

[ -z "$findings" ] && exit 0

jq -n --arg ctx "문서-코드 불일치가 감지되었다. 관련 작업을 할 때 문서를 함께 갱신하라.

${findings}" \
  '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: $ctx
    }
  }'
exit 0
