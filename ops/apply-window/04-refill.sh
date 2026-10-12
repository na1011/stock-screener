#!/bin/bash
# 사용: 04-refill.sh <db> <true|false> [연수=11]  — 현재 편입(--universe) → 과거 편입(--tickers) · 재시도 가능 실패는 같은 DB에서 곧바로 재시도
source "$(dirname "$0")/env.sh"
DB="$1"; FLAG="$2"; YEARS="${3:-11}"; [ "$FLAG" = true ] || [ "$FLAG" = false ] || die "플래그는 true|false"
read N_ALL N_CUR N_PAST < $A/counts.txt
TP=$(cat $A/tpast.csv); [ "$(echo "$TP" | tr ',' '\n' | grep -c .)" -eq "$N_PAST" ] || die "과거 편입 파일 수가 counts와 다르다"
guard_clock() { [ "$DB" = "$S1" ] || [ "$DB" = "$PROD" ] || [ "$(date +%H%M)" -lt 1850 ] || die "S0 refill은 18:50 KST 전에만 — SEC 접수 창"; }
NOTES=$A/notes-$DB-$FLAG-$YEARS.txt; : > $NOTES
run() { # <라벨> <기대 target> <인자…>
  local label="$1" expect="$2"; shift 2; guard_clock
  local P id; P=$(max_exec_id $DB)
  boot $DB --job=financial-refill "$@" --financial-years=$YEARS --insert-earlier-vintages=$FLAG > $A/refill-$label-$DB-$FLAG-$YEARS.log 2>&1 || true
  id=$(new_exec $DB "$P" financial-refill "$expect")
  echo "$label $(q $DB "select note from batch_execution where id=$id")" | tee -a $NOTES
  failures_of $DB $id > $A/fail-$DB-$id.txt
  local retry; retry=$(grep -E ":($(echo $RETRYABLE | tr ' ' '|'))\$" $A/fail-$DB-$id.txt | cut -d: -f1 | paste -sd, - || true)
  local other; other=$(grep -v -x -F -f <(tr ' ' '\n' <<< "$EXPECTED_FAILURES") $A/fail-$DB-$id.txt | grep -vE ":($(echo $RETRYABLE | tr ' ' '|'))\$" || true)
  echo "  실패: $(tr '\n' ' ' < $A/fail-$DB-$id.txt)"
  [ -z "$other" ] || die "기대 밖·재시도 불가 실패: $other"
  if [ -n "$retry" ]; then
    local n; n=$(echo "$retry" | tr ',' '\n' | grep -c .); P=$(max_exec_id $DB)
    boot $DB --job=financial-refill --tickers=$retry --financial-years=$YEARS --insert-earlier-vintages=$FLAG > $A/refill-retry-$DB-$id.log 2>&1 || true
    id=$(new_exec $DB "$P" financial-refill "$n"); echo "retry($label) $(q $DB "select note from batch_execution where id=$id")" | tee -a $NOTES
    # 재시도에서도 기대 실패는 뺀다 — 첫 실행에서 TRANSIENT였던 기대 종목(예: 매핑 판을 못 받은 실행의 APGE)이 재시도에서는
    # 신선한 판의 NOT_FOUND로 돌아온다. 첫 실행 갈래와 같은 술어를 써야 두 갈래가 같은 실패를 다르게 판정하지 않는다.
    failures_of $DB $id > $A/fail-$DB-$id.txt
    local left; left=$(grep -v -x -F -f <(tr ' ' '\n' <<< "$EXPECTED_FAILURES") $A/fail-$DB-$id.txt || true)
    [ -z "$left" ] || die "재시도에도 실패: $(tr '\n' ' ' <<< "$left")"
  fi
}
run cur "$N_CUR" --universe=US_TOP1000
run past "$N_PAST" --tickers=$TP
echo "OK $NOTES"
