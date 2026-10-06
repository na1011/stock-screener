#!/bin/bash
source "$(dirname "$0")/env.sh"
(cd $REPO && git rev-parse HEAD) > $A/head.txt
echo "PROD=$PROD S1=$S1 S0=$S0 HEAD=$(cat $A/head.txt)"
[ -z "$(cd $REPO && git status --porcelain --untracked-files=no)" ] || die "작업 트리에 추적 파일 변경이 있다"
r=$(q $PROD "select count(*) from batch_execution where status='RUNNING'"); [ "$r" = 0 ] || die "RUNNING $r"
# 적재 JVM 판정은 훅·ops/crons-done.sh와 같은 함수다(따로 짠 grep은 환경변수 프로파일을 놓쳤다 · 2026-10-06 반증). 판정 불가(2)도 멈춘다.
. "$REPO/.claude/hooks/lib/load-process.sh"; lp=0; stray_load_pids >/dev/null || lp=$?
[ "$lp" = 1 ] || die "bootRun이 떠 있거나 판정하지 못했다(stray_load_pids rc=$lp)"
# 창은 15:30 뒤에 열린다 — 그 전에는 오늘 13:30·14:00이 아직 안 돌았을 수 있어 검사하지 않는다.
if [ "$PROD" = "$LIVE" ] && [ "$(date +%u)" -le 5 ] && [ "$(date +%H%M)" -ge 1510 ]; then
  for job in daily-update corporate-action; do
    s=$(q $PROD "select status from batch_execution where job_name='$job' and started_at::date=current_date order by id desc limit 1")
    [ -n "$s" ] && [ "$s" != RUNNING ] || die "오늘 $job 실행이 없거나 아직 돈다($s)"
  done
fi
[ -z "$(cd $REPO && git diff --stat 8198fe0..HEAD -- src/main/resources/db)" ] || die "운영 jar 뒤 마이그레이션 변화가 있다"
LATEST=$(q $PROD "select max(trade_date) from indicator_trade_date where market_code='US'"); [ -n "$LATEST" ] || die "LATEST가 비었다"; echo "LATEST=$LATEST" > $A/latest.txt
TICK="select distinct m.stock_id from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active where s.is_active and s.market_code='US' and (m.effective_to is null or (m.effective_from <= (select max(trade_date) from indicator_trade_date where market_code='US') and m.effective_to > (select min(trade_date) from indicator_trade_date where market_code='US')))"
CUR="select distinct m.stock_id from universe_membership m join stock s on s.id=m.stock_id where m.universe_code='US_TOP1000' and m.effective_to is null and s.is_active and s.market_code='US'"
q $PROD "select string_agg(ticker, ',' order by ticker) from stock where id in ($TICK)" > $A/t1207.csv
q $PROD "select string_agg(ticker, ',' order by ticker) from stock where id in ($TICK) and id not in ($CUR)" > $A/tpast.csv
N_ALL=$(tr ',' '\n' < $A/t1207.csv | grep -c .); N_PAST=$(tr ',' '\n' < $A/tpast.csv | grep -c .); N_CUR=$(q $PROD "select count(*) from ($CUR) c")
N_DUP=$(q $PROD "select count(*) - count(distinct ticker) from stock where id in ($TICK)"); [ "$N_DUP" = 0 ] || die "대상 티커 중복 $N_DUP"
echo "전체=$N_ALL 현재=$N_CUR 과거=$N_PAST"; [ $((N_CUR + N_PAST)) -eq "$N_ALL" ] || die "현재+과거≠전체"; [ "$N_PAST" -gt 0 ] || die "과거 편입 0"
echo "$N_ALL $N_CUR $N_PAST" > $A/counts.txt
echo OK
