#!/bin/bash
# 분사 배포 저녁 절차(2026-10-05 · 사용자 승인: 15:40 뒤 배포 · 판정 16행). 단계마다 따로 부른다: steps.sh <단계>
set -uo pipefail
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
REPO=/Users/na1011/dev/stock-screener
export Q4V_WORK=$S/spin-prod
PSQL=/opt/homebrew/opt/postgresql@16/bin/psql
q() { $PSQL -d stock_screener -v ON_ERROR_STOP=1 -At -F $'\t' -c "$1"; }
live() { (cd $REPO && ./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap --server.port=0 $*"); }
step="$1"; shift || true
case "$step" in
  pre)  # 도는 배치가 없어야 한다 · 15:40 뒤
        date '+%H:%M'; q "select id, job_name, status from batch_execution where status='RUNNING'"; (cd $REPO && git status --short | head; git log --oneline -1) ;;
  ca)   live --job=corporate-action > $S/spin-prod/ca.log 2>&1; echo rc=$?; q "select id, status, target_count, success_count, failure_count from batch_execution where job_name='corporate-action' order by id desc limit 1" ;;
  ids)  q "select ca.id, s.ticker, ca.effective_date, ca.type, ca.status from corporate_action ca join stock s on s.id=ca.stock_id where s.market_code='US' and (s.ticker, ca.effective_date) in (('FTI','2021-02-16'),('LEN','2025-01-21'),('LBTYB','2024-11-13'),('SYRE','2023-07-03'),('DNTH','2023-09-11'),('CNH','2022-01-03'),('FTV','2020-10-09'),('DELL','2021-11-02'),('HON','2026-06-29'),('OPEN','2025-11-18')) order by 2" ;;
  judge) # 인자: <id> 나머지 판정 인자들
        id=$1; shift; live --job=corporate-action-judge --ca-id=$id "$@" > $S/spin-prod/judge-$id.log 2>&1; echo "id=$id rc=$?"; q "select id, type, status, ratio, price_ratio, left(note,80) from corporate_action where id=$id" ;;
  rebuild) live --job=adjustment-rebuild > $S/spin-prod/rebuild.log 2>&1; echo rc=$?; q "select id, status, target_count, success_count, failure_count, left(coalesce(note,''),200) from batch_execution where job_name='adjustment-rebuild' order by id desc limit 1"; grep -c "수정계수를 원장에서 다시 지었다" $S/spin-prod/rebuild.log ;;
  reconcile) live --job=indicator-reconcile > $S/spin-prod/reconcile.log 2>&1; echo rc=$?; q "select id, job_name, status, target_count, success_count, failure_count, left(coalesce(note,''),200) from batch_execution order by id desc limit 3" ;;
  *) echo "단계: pre ca ids judge rebuild reconcile" ;;
esac
