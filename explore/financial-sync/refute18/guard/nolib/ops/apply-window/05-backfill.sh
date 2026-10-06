#!/bin/bash
# 사용: 05-backfill.sh <db>  — 원본층(대상 전체 · 날짜 하나) → 파생층(1종목 · 창 루프). 원본층에 실패가 있어도 파생층까지 돌고 끝에서 멈춘다.
source "$(dirname "$0")/env.sh"
DB="$1"; read N_ALL N_CUR N_PAST < $A/counts.txt; LATEST=$(cut -d= -f2 $A/latest.txt)
T=$(cat $A/t1207.csv); [ "$(echo "$T" | tr ',' '\n' | grep -c .)" -eq "$N_ALL" ] || die "대상 파일 수가 다르다"
P=$(max_exec_id $DB); boot $DB --job=backfill --tickers=$T --from=$LATEST --as-of=$LATEST > $A/backfill-base-$DB.log 2>&1 || true
BASE=$(new_exec $DB "$P" indicator-backfill "$N_ALL"); echo "원본층 $(q $DB "select status, target_count, success_count, failure_count from batch_execution where id=$BASE")"
P=$(max_exec_id $DB); boot $DB --job=backfill --tickers=AAPL --from=2025-09-01 --as-of=$LATEST > $A/backfill-derived-$DB.log 2>&1 || true
DER=$(new_exec $DB "$P" indicator-backfill 1); echo "파생층 $(q $DB "select status, target_count, success_count, failure_count from batch_execution where id=$DER")"
fb=$(q $DB "select failure_count from batch_execution where id=$BASE"); fd=$(q $DB "select failure_count from batch_execution where id=$DER")
[ "$fb" -eq 0 ] && [ "$fd" -eq 0 ] || die "백필 실패 원본층 $fb · 파생층 $fd — 실패 종목: $(failures_of $DB $BASE | tr '\n' ' ')"
echo OK
