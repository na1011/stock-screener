#!/bin/bash
# 사용: 04b-depositary.sh   — 쓰기 세션 안에서. 예탁 판 비율(B · ADR-0183)의 관문 절차 — docs/reload-gate.md B 행 그대로.
# 05보다 먼저다: 두 순위(오늘 · 과거 날짜)가 DEPOSITARY 기저를 읽는다(UsMarketCapUniverseAdapter · UsPastRanking의 depositaryStocks) —
# 이것 없이 05가 돌면 두 평면 모두 ADS를 이름으로 뺀 채 짓는다(관측 평면 계획 v1.1 · 반증 밖 1).
#   ① depositary-splits — 예탁증권 줄 발행사의 2019 뒤 ADS 사건(종목당 1콜)
#   ② adjustment-rebuild — 그 사건으로 계수(기본 대상 = 창 편입 ∪ 창 밖 적용 대기)
#   ③ share-basis-derive — k → 클래스 축 → 기저(03 뒤 자동 판정은 ① 앞이라 사건을 못 봤다 · 한 번 더)
#   ④ 단언 — DEPOSITARY 기저 행이 있다(관문 전 운영 0행 · 10-23)
source "$(dirname "$0")/env.sh"
LOG="$A/depositary-$PROD"
P=$(max_exec_id "$PROD")
boot "$PROD" --job=depositary-splits $GATE_ARGS > "$LOG-splits.log" 2>&1 || true
gate_line "$LOG-splits.log"; new_exec "$PROD" "$P" depositary-splits - > /dev/null
P=$(max_exec_id "$PROD")
boot "$PROD" --job=adjustment-rebuild $GATE_ARGS > "$LOG-rebuild.log" 2>&1 || true
gate_line "$LOG-rebuild.log"; new_exec "$PROD" "$P" adjustment-rebuild - > /dev/null
boot "$PROD" --job=share-basis-derive $GATE_ARGS > "$LOG-derive.log" 2>&1 || true
gate_line "$LOG-derive.log"
grep -qE "주식수 기저 판정\. 시장=US " "$LOG-derive.log" || die "기저 판정 줄이 없다 — $LOG-derive.log"
grep -E "주식수 기저 판정\. 시장=US " "$LOG-derive.log" | sed 's/^.*INFO [^ ]* -- //'
N=$(q "$PROD" "select count(*) from financial_statement f join stock s on s.id=f.stock_id where s.market_code='US' and f.share_basis_status='DEPOSITARY'")
[ "$N" -gt 0 ] || die "DEPOSITARY 기저 행이 0이다 — 표지 비율(sec_depositary_ratio)이 안 실렸거나 관문 인자 없이 돌았다"
echo "OK 예탁 판 비율 — DEPOSITARY 행 $N"
