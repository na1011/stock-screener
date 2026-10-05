#!/bin/bash
# 분사 드라이런 — 재구축 → 화해(①②). 외부 호출 0. 사본에서만. a = 배포 코드 · b = 새 코드.
set -uo pipefail
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
step() { # <side> <worktree> <db> <label> <job args...>
  local side=$1 wt=$2 db=$3 label=$4; shift 4
  export Q4V_WORK=$S/spin-dry/$side
  (cd $wt && git rev-parse HEAD) > $Q4V_WORK/head.txt
  source $wt/ops/apply-window/env.sh
  set +e
  local before; before=$(max_exec_id $db)
  local t0=$(date +%s)
  echo "[$(date +%T)] $side $label start"
  boot $db "$@" > $Q4V_WORK/$label.log 2>&1
  local rc=$?
  echo "[$(date +%T)] $side $label rc=$rc $(( $(date +%s) - t0 ))s"
  q $db "select id, job_name, status, target_count, success_count, failure_count, left(coalesce(note,''),200) from batch_execution where id > $before order by id"
}
for side in a b; do
  if [ $side = a ]; then wt=$S/wt-holes-a; db=stock_screener_sp_a; else wt=$S/wt-holes; db=stock_screener_sp_b; fi
  step $side $wt $db rebuild --job=adjustment-rebuild --as-of=2026-10-02
  step $side $wt $db reconcile --job=indicator-reconcile --as-of=2026-10-02
done
echo DONE
