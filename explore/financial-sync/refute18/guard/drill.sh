#!/bin/bash
# deploy.sh 시험 DB 가드 연습 — 가짜 홈(앱 PID 파일 없음 → 크론 문·종료 안 탐) · 가짜 gradlew(호출만 적고 rc 1)
G=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/wt-explore/explore/financial-sync/refute18/guard
: > $G/gradle-calls.log
for t in tm t0 t1; do
  for d in v62 v63 v64 empty old64; do
    db=stock_screener_r18g_${d}_test
    out=$(HOME=$G/fakehome TEST_DB_URL=jdbc:postgresql://localhost:5432/$db bash $G/$t/ops/deploy.sh 2>&1); rc=$?
    built=$(grep -c "tree=$t .*$db" $G/gradle-calls.log)
    msg=$(echo "$out" | grep -E '\[deploy\]' | head -1 | cut -c1-140)
    printf '%s\t%s\trc=%s\tbuild호출=%s\t%s\n' "$t" "$d" "$rc" "$built" "$msg"
  done
done
