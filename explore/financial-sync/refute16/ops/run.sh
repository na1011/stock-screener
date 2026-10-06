#!/bin/bash
# main 70e10d57 ops의 crons-done — 가짜 홈 · 가짜 ps/jcmd/date · DB stock_screener_r16b_ops. 앱·운영 DB 무접촉.
R="$1"; DPID=$(cat $R/ops/dummy.pid); OPS=$R/ops/opsmain/ops
scenario() {  # <이름> <FAKE_NOW> <JVM 기동> <끝 줄들>
  local name="$1" now="$2" start="$3"; shift 3
  local H=$R/ops/homes/$name; mkdir -p "$H/Library/Application Support/stock-screener" "$H/Library/Logs/stock-screener"
  echo "$DPID" > "$H/Library/Application Support/stock-screener/app.pid"
  : > "$H/Library/Logs/stock-screener/stdout.log"
  for e in "$@"; do echo "${e%% *}.500+09:00  INFO $DPID --- [stock-screener] [   scheduling-1] c.s.p.scheduler.PipelineScheduler        : 크론 발화를 마쳤다. 크론=${e#* }" >> "$H/Library/Logs/stock-screener/stdout.log"; done
  echo "== $name · 지금 $now · 기동 $start · 끝 줄 $#개"
  env HOME="$H" PATH=$R/ops/shim:$PATH FAKE_NOW="$now" FAKE_APP_PID=$DPID FAKE_JVM_START="$start" DB_NAME=stock_screener_r16b_ops bash $OPS/crons-done.sh; echo "rc=$?"; echo
}
S="2026-10-06 18:44:06"
WED=("2026-10-07T13:53:51 runDailyUpdate" "2026-10-07T14:12:21 runCorporateActionChain" "2026-10-07T14:15:00 runHeavyJobOfTheDay" "2026-10-07T14:18:02 deriveReconcileInputs" "2026-10-07T14:26:11 reconcileIndicators" "2026-10-07T15:30:01 checkJobFreshness")
FRI=("2026-10-09T13:53:51 runDailyUpdate" "2026-10-09T14:12:21 runCorporateActionChain" "2026-10-09T15:10:00 runHeavyJobOfTheDay" "2026-10-09T15:12:02 deriveReconcileInputs" "2026-10-09T15:20:11 reconcileIndicators" "2026-10-09T15:30:01 checkJobFreshness")
case "${2:-base}" in
base)
scenario S1-tonight "2026-10-06 19:45:00" "$S"
scenario S2-wed-0900 "2026-10-07 09:00:00" "$S"
scenario S3-wed-1325 "2026-10-07 13:25:30" "$S"
scenario S3b-wed-1324 "2026-10-07 13:24:59" "$S"
scenario S4-wed-1331-running "2026-10-07 13:31:00" "$S"
scenario S4b-wed-1330-early-noop "2026-10-07 13:30:01" "$S" "2026-10-07T13:29:58 runDailyUpdate"
scenario S5-wed-1600-day-done "2026-10-07 16:00:00" "$S" "${WED[@]}"
scenario S5b-wed-1600-1530-missing "2026-10-07 16:00:00" "$S" "${WED[@]:0:5}"
scenario S6-sat-1900 "2026-10-10 19:00:00" "$S" "${WED[@]}" "${FRI[@]}"
scenario S6b-sat-1956 "2026-10-10 19:56:00" "$S" "${WED[@]}" "${FRI[@]}"
scenario S7-sat-2005-rebalancing "2026-10-10 20:05:00" "$S" "${WED[@]}" "${FRI[@]}"
scenario S8-sat-2100-done "2026-10-10 21:00:00" "$S" "${WED[@]}" "${FRI[@]}" "2026-10-10T20:41:00 runUniverseRebalance"
scenario S9-p0-deploy-jvm-wed-1610 "2026-10-07 16:30:00" "2026-10-07 16:10:00"
;;
rows)
scenario G-tonight-with-rows "2026-10-06 19:45:00" "$S"
;;
esac
