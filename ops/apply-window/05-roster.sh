#!/bin/bash
# 사용: 05-roster.sh   — 쓰기 세션 안에서. 명부 다시 짓기(계획 v2 결정 7) — 재적재가 순위 재료(재무 · 클래스 축 판정)를 바꿨으므로
# ADR-0135의 「재무·원장이 바뀌면 다시 돈다」가 그대로 선다. 차이가 있을 때만 도는 갈래는 없다 — 언제나 돈다.
#   ① 순위 가드 — 세션 훅 guard-rebalance-on-dirty-rank.sh를 이 DB로 그대로 부른다(질의 사본을 안 만든다). 스크립트로 감싼 실행은
#      훅이 명령 문자열에서 gradlew·--job=universe를 못 봐 안 걸린다(계획 밖 반증 3).
#   ② market-calendar — 복원이 읽는 미국 세션 달력(정기 경로에 없다 · docs/reload-gate.md 순서 5).
#   ③ universe-restore --rebuild-observed=true --as-of=L --apply=true --replace=true(맨 플래그는 BootstrapRunner가 거부한다) — 관측 평면까지
#      켬 기저로 다시 짓는다(탐색 explore/universe/OBSERVED-PLANE-PLAN-1023.md v1.1 · ADR-0135 「관문 날 관측 하한이 옮겨간다」):
#      L = 미국 마지막 마감 세션(② 뒤 달력) · 보존 창 첫날 ~ L은 과거 날짜 순위 한 사슬 · 열린 행은 안 지우고 시작일만 L ·
#      같은 실행 안에서 L 같은 기준일 리밸런싱(앞 편입 = 과거 순위 L 명부)이 지금 편입을 고른다 — 그 실행 행 SUCCESS를 단언한다.
#      MUST: 04b(예탁 판 비율) 뒤다 — 두 순위가 DEPOSITARY 기저를 읽는다.
#      되돌리기 재료는 01 덤프뿐이다 — 02의 membership.tsv에는 id · stock_id · created_at이 없다.
#   ④ 창 결손 0 — 활성 유니버스 · 보존 창과 겹치는 편입 행 중 자기 구간에 봉이 없는 종목 0(복원이 처음 넣은 종목 · V·STZ·FWONA) ·
#      거버넌스 원문이 0행인 종목 0(그 시장에 원문이 있을 때 — MembershipGap GOVERNANCE 축과 같은 뜻).
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
L=$(q "$PROD" "select max(session_date) from market_calendar where exchange_code='NYSE' and core_end < now()")
[ -n "$L" ] || die "미국 마지막 마감 세션을 못 구했다 — ② 달력"
OBS_MAX=$(q "$PROD" "select max(greatest(effective_from, coalesce(effective_to, effective_from))) from universe_membership where universe_code='US_TOP1000' and origin='OBSERVED'")
if [[ "$OBS_MAX" > "$L" ]]; then die "관측 원장에 L($L)보다 늦은 날짜 $OBS_MAX가 있다"; fi
echo "  L=$L (관측 원장 최신 $OBS_MAX)"
P=$(max_exec_id "$PROD")
boot "$PROD" --job=universe-restore --universe=US_TOP1000 --as-of=$L --apply=true --replace=true --rebuild-observed=true $GATE_ARGS \
  > "$LOG-restore.log" 2>&1 || true
gate_line "$LOG-restore.log"
if grep -qE "관측 평면을 켬 기저로 다시 지었다\. US_TOP1000 " "$LOG-restore.log"; then
  new_exec "$PROD" "$P" universe-rebalance - > /dev/null
  FLOOR=$(q "$PROD" "select min(effective_from) from universe_membership where universe_code='US_TOP1000' and origin='OBSERVED'")
  [ "$FLOOR" = "$L" ] || die "관측 하한 $FLOOR ≠ L $L"
  N_OPEN=$(q "$PROD" "select count(*) from universe_membership where universe_code='US_TOP1000' and effective_to is null")
  [ "$PROD" != "$LIVE" ] || [ "$N_OPEN" = 1000 ] || die "지금 편입 $N_OPEN ≠ 1000"
  grep -E "관측 평면 다시 짓기 계획|재생 대조\. US_TOP1000|L 오늘 순위 대 과거 순위|관측 평면을 켬 기저로 다시 지었다" "$LOG-restore.log" \
    | sed 's/^.*INFO [^ ]* -- //' | tee "$A/roster-attribution-$PROD.txt"
else
  # 작은 DB에서는 원리적으로 못 돈다 — 순위 어댑터가 후보 1,000 미만이면 부분 목록을 반영하지 않고 선다(그것이 맞다). 인자 검사를 지나
  # 스냅숏까지 간 것만 확인하고 넘어간다. MUST NOT: 운영(LIVE)에는 이 갈래를 열지 않는다.
  if [ "$PROD" != "$LIVE" ] && grep -q "상위 1000를 채우지 못한다" "$LOG-restore.log"; then
    echo "  (작은 DB — 후보가 1,000에 못 미쳐 복원 스냅숏이 선다 · 인자 검사는 지났다 · 복원 자체는 이 드라이런이 못 잰다)"
  else
    die "다시 짓기 완료 줄이 없다 — $LOG-restore.log"
  fi
fi
# ④
GAP=$(q "$PROD" "with w as (select market_code, min(trade_date) lo from indicator_trade_date group by 1)
  select coalesce(string_agg(distinct s.ticker, ',' order by s.ticker), '') from universe_membership m
  join universe u on u.code=m.universe_code and u.is_active join stock s on s.id=m.stock_id join w on w.market_code=s.market_code
  where (m.effective_to is null or m.effective_to > w.lo)
    and not exists (select 1 from price_daily p where p.stock_id=m.stock_id and p.trade_date >= greatest(m.effective_from, w.lo)
                      and (m.effective_to is null or p.trade_date < m.effective_to))")
[ -z "$GAP" ] || die "창 안 편입 중 자기 구간에 봉이 없는 종목: $GAP — 복원 꼬리의 결손 보충이 안 돌았다"
GOV=$(q "$PROD" "with w as (select market_code, min(trade_date) lo from indicator_trade_date group by 1)
  select coalesce(string_agg(distinct s.ticker, ',' order by s.ticker), '') from universe_membership m
  join universe u on u.code=m.universe_code and u.is_active join stock s on s.id=m.stock_id join w on w.market_code=s.market_code
  where (m.effective_to is null or m.effective_to > w.lo)
    and not exists (select 1 from governance_report_row r where r.stock_id=s.id)
    and exists (select 1 from governance_report_row r2 join stock s2 on s2.id=r2.stock_id where s2.market_code=s.market_code)")
[ -z "$GOV" ] || die "창 안 편입 중 거버넌스 원문이 0행인 종목: $GOV — 복원 꼬리 · 리밸런싱 꼬리의 거버넌스 보충이 안 돌았다"
echo "OK 명부 — 순위 가드 통과 · 달력 · 관측 평면 다시 짓기(L=$L) · 창 결손 0(봉 · 거버넌스)"
