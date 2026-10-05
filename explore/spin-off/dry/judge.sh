#!/bin/bash
# 분사 드라이런 (g)(j) + OPEN 재수집(V62) — 사본 b에서만. 판정 note는 공백 없이 쓴다(boot가 --args를 큰따옴표로 감싼다).
set -uo pipefail
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
export Q4V_WORK=$S/spin-dry/b
WT=$S/wt-holes; DB=stock_screener_sp_b
(cd $WT && git rev-parse HEAD) > $Q4V_WORK/head.txt
source $WT/ops/apply-window/env.sh
set +e
run() { local label=$1; shift; local t0=$(date +%s); echo "[$(date +%T)] $label start"; boot $DB "$@" > $Q4V_WORK/$label.log 2>&1; echo "[$(date +%T)] $label rc=$? $(( $(date +%s) - t0 ))s"; }
run ca-open --job=corporate-action --as-of=2026-10-02 --tickers=OPEN
run judge-hon --job=corporate-action-judge --ca-id=143 --ca-status=APPROVED --ca-type=SPIN_OFF --ca-ratio=0.5 --ca-price-ratio=0.9832967515 --ca-note=병합1:2+HONA분사·같은날종가·존속0.5×227.80·자식0.5×220.19·v0.5084934932
run judge-880 --job=corporate-action-judge --ca-id=210 --ca-price-ratio=0.8579507517 --ca-note=인적분할·재상장일시총·존속6298142735700·신설0220W0보통846000505350·v0.8816
run judge-207940 --job=corporate-action-judge --ca-id=53 --ca-status=APPROVED --ca-type=SPIN_OFF --ca-ratio=0.6503913086 --ca-price-ratio=0.7360835452 --ca-note=인적분할·재상장일시총·존속82814511339000·삼성에피스홀딩스0126Z0·10911216986500·v0.8836
q $DB "select s.ticker, ca.effective_date, ca.type, ca.status, ca.ratio, ca.price_ratio from corporate_action ca join stock s on s.id=ca.stock_id where ca.id in (143,210,53) or (s.ticker='OPEN')"
run rebuild2 --job=adjustment-rebuild --as-of=2026-10-02 --tickers=OPEN,HON,000880,207940
run reconcile2 --job=indicator-reconcile --as-of=2026-10-02 --tickers=OPEN,HON,000880,207940
q $DB "select id, job_name, status, target_count, success_count, failure_count, left(coalesce(note,''),200) from batch_execution where id > 725 order by id"
echo DONE
