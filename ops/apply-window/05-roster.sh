#!/bin/bash
# 사용: 05-roster.sh   — 쓰기 세션 안에서. 명부 다시 짓기(계획 v2 결정 7) — 재적재가 순위 재료(재무 · 클래스 축 판정)를 바꿨으므로
# ADR-0135의 「재무·원장이 바뀌면 다시 돈다」가 그대로 선다. 차이가 있을 때만 도는 갈래는 없다 — 언제나 돈다.
#   ① 순위 가드 — 세션 훅 guard-rebalance-on-dirty-rank.sh를 이 DB로 그대로 부른다(질의 사본을 안 만든다). 스크립트로 감싼 실행은
#      훅이 명령 문자열에서 gradlew·--job=universe를 못 봐 안 걸린다(계획 밖 반증 3).
#   ② market-calendar — 복원이 읽는 미국 세션 달력(정기 경로에 없다 · docs/reload-gate.md 순서 5).
#   ③ universe-restore --apply=true --replace=true(맨 플래그는 BootstrapRunner가 거부한다) — 관측 하한 앞 격자를 켬 기저로 다시 짓는다.
#      MUST: 관측 평면(08-31~)도 옛 기저다 — 그것을 다시 짓는 잡은 클래스 축 묶음의 설계 항목이고(docs/reload-gate.md 「관측 평면이 옛 순위
#      기저로 남음」), 그 잡이 생기면 ③을 그것으로 바꾼다. 지금 ③만으로는 관문이 닫히지 않는다.
#   ④ 창 결손 0 — 활성 유니버스 · 보존 창과 겹치는 편입 행 중 자기 구간에 봉이 없는 종목 0(복원이 처음 넣은 종목 · V·STZ·FWONA).
source "$(dirname "$0")/env.sh"
LOG="$A/roster-$PROD"
# ①
g=$(jq -n '{tool_input:{command:"./gradlew bootRun --args=\"--job=universe-restore --universe=US_TOP1000 --apply=true\""}}' \
      | DB_NAME="$PROD" bash "$REPO/.claude/hooks/guard-rebalance-on-dirty-rank.sh") || die "순위 가드가 판정하지 못했다"
[ -z "$g" ] || die "순위 가드가 막는다: $(echo "$g" | jq -r '.hookSpecificOutput.permissionDecisionReason' | cut -c1-600)"
# ②
P=$(max_exec_id "$PROD")
boot "$PROD" --job=market-calendar $GATE_ARGS > "$LOG-calendar.log" 2>&1 || true
gate_line "$LOG-calendar.log"; new_exec "$PROD" "$P" market-calendar - > /dev/null
# ③
boot "$PROD" --job=universe-restore --universe=US_TOP1000 --apply=true --replace=true $GATE_ARGS > "$LOG-restore.log" 2>&1 || true
gate_line "$LOG-restore.log"
grep -E "과거 편입을 복원했다\. US_TOP1000 " "$LOG-restore.log" || die "복원 완료 줄이 없다 — $LOG-restore.log"
grep -E "복원 계획\. US_TOP1000 |stock에 없는 종목" "$LOG-restore.log" | sed 's/^.*INFO [^ ]* -- //' || true
# ④
GAP=$(q "$PROD" "with w as (select market_code, min(trade_date) lo from indicator_trade_date group by 1)
  select coalesce(string_agg(distinct s.ticker, ',' order by s.ticker), '') from universe_membership m
  join universe u on u.code=m.universe_code and u.is_active join stock s on s.id=m.stock_id join w on w.market_code=s.market_code
  where (m.effective_to is null or m.effective_to > w.lo)
    and not exists (select 1 from price_daily p where p.stock_id=m.stock_id and p.trade_date >= greatest(m.effective_from, w.lo)
                      and (m.effective_to is null or p.trade_date < m.effective_to))")
[ -z "$GAP" ] || die "창 안 편입 중 자기 구간에 봉이 없는 종목: $GAP — 복원 꼬리의 결손 보충이 안 돌았다"
echo "OK 명부 — 순위 가드 통과 · 달력 · 복원 · 창 결손 0"
