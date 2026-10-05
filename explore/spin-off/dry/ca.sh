#!/bin/bash
# 분사 드라이런 — 기업행위 수집(Alpaca·DART 호출). a = 배포 코드(36d0325d) · b = 새 코드(8a84ad66). 사본에서만.
set -uo pipefail
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
run() { # <side> <worktree> <db> <label>
  export Q4V_WORK=$S/spin-dry/$1
  (cd $2 && git rev-parse HEAD) > $Q4V_WORK/head.txt
  source $2/ops/apply-window/env.sh
  set +e
  local before; before=$(max_exec_id $3)
  echo "[$(date +%T)] $4 start (exec>$before)"
  boot $3 --job=corporate-action --as-of=2026-10-02 > $Q4V_WORK/$4.log 2>&1
  local rc=$?
  echo "[$(date +%T)] $4 rc=$rc"
  q $3 "select id, status, target_count, success_count, failure_count, left(coalesce(note,''),300) from batch_execution where id > $before and job_name='corporate-action'"
}
run a $S/wt-holes-a stock_screener_sp_a ca1
run b $S/wt-holes stock_screener_sp_b ca1
run b $S/wt-holes stock_screener_sp_b ca2
echo DONE
