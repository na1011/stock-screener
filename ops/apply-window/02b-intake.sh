#!/bin/bash
# 사용: 02b-intake.sh   — 쓰기 세션 안에서($PROD 가드 켬). 보존 창 안 폐지 실체 중 행 없는 것을 행으로 들인다(us-delisted-intake --apply=true ·
# 탐색 US-PAST-LISTING-IMPL-1018.md U3·U6). 03-refill 앞이어야 한다 — 그 재적재(cand)가 들인 행의 업종·재무를 받는다.
# 들인 행은 후보 모집단(활성 편입 없음 · 시대가 보존 창과 겹침)에 들므로 counts.txt의 후보 수를 다시 센다(03의 대상 상한).
source "$(dirname "$0")/env.sh"
read N_CUR N_CAND N_CAND_FIN < "$A/counts.txt"
P=$(max_exec_id "$PROD")
BEFORE=$(q "$PROD" "select count(*) from stock where market_code='US'")
boot "$PROD" --job=us-delisted-intake --apply=true $GATE_ARGS > "$A/intake-$PROD.log" 2>&1 || true
gate_line "$A/intake-$PROD.log"
id=$(new_exec "$PROD" "$P" us-delisted-intake -)
NOTE=$(q "$PROD" "select note from batch_execution where id=$id")
AFTER=$(q "$PROD" "select count(*) from stock where market_code='US'")
MADE=$(echo "$NOTE" | sed -nE 's/.*만든 행 ([0-9]+).*/\1/p')
[ -n "$MADE" ] || die "인입 실행 요약에서 만든 행 수를 못 읽었다: $NOTE"
[ "$((AFTER - BEFORE))" = "$MADE" ] || die "미국 행이 $((AFTER - BEFORE)) 늘었는데 요약은 만든 행 $MADE"
# 들인 행은 전부 비운 표지 · 등록인 · 시대 끝이 있어야 한다(펜스 「닫힌 시대인데 표지에 ~가 없는 행 0」과 같은 물음).
BAD=$(q "$PROD" "select count(*) from stock where market_code='US' and created_at >= (select started_at from batch_execution where id=$id) and (strpos(ticker,'~')=0 or sec_cik is null or era_to is null)")
[ "$BAD" = 0 ] || die "들인 행 중 비운 표지·등록인·시대 끝이 빠진 것 $BAD"
WIN="(select min(trade_date) from indicator_trade_date where market_code='US')"
N_CAND=$(q "$PROD" "select count(*) from stock s where s.market_code='US' and s.is_active and not exists (select 1 from universe_membership am join universe au on au.code=am.universe_code and au.is_active where am.stock_id=s.id and am.effective_to is null) and (s.era_to is null or s.era_to > $WIN) and (s.era_from is null or s.era_from <= (now() at time zone 'America/New_York')::date)")
echo "$N_CUR $N_CAND $N_CAND_FIN" > "$A/counts.txt"
echo "intake id=$id · $NOTE · 후보 다시 셈 $N_CAND" | tee -a "$A/notes-$PROD.txt"
