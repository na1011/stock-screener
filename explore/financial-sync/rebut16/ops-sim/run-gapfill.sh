#!/bin/bash
# rebut16 ops-sim: 가짜 홈 · 가짜 앱(sleep) · 내 시험 DB(stock_screener_r16a_test)만. 진짜 홈·앱(15406)·운영 DB를 안 건드린다.
set -u
O="$(cd "$(dirname "$0")" && pwd)"
PSQL=/opt/homebrew/opt/postgresql@16/bin/psql
DB=stock_screener_r16a_test
home="$O/home-gf-$$"; mkdir -p "$home/Library/Application Support/stock-screener" "$home/Library/Logs/stock-screener"; : > "$home/Library/Logs/stock-screener/stdout.log"
bash -c 'exec -a "java -jar /sim/app.jar --pipeline.scheduler.enabled=true" /bin/sleep 600' & APP=$!; sleep 0.5
echo $APP > "$home/Library/Application Support/stock-screener/app.pid"
echo "가짜 앱 PID $APP · 홈 $home · 지금 $(date '+%F %T')"
run() { (cd "$O/repo" && HOME="$home" DB_NAME=$DB PATH="/opt/homebrew/opt/postgresql@16/bin:/usr/bin:/bin:/usr/sbin:/sbin" bash "$@"); }
q() { $PSQL -X -At -d $DB -c "$1"; }
echo "--- 대조: RUNNING 표"; q "select id, job_name, triggered_by, status from batch_execution where status='RUNNING'"
echo "=== K0 RUNNING 없음"; run ops/crons-done.sh; echo "rc=$?"
ID1=$(q "insert into batch_execution(job_name,target_date,status,triggered_by,started_at,note) values ('indicator-backfill',current_date-2,'RUNNING','MANUAL',now()-interval '2 days','rebut16 sim MANUAL 유령') returning id" | head -1)
echo "=== K1 MANUAL 유령(id $ID1)"; run ops/crons-done.sh; echo "rc=$?"
ID2=$(q "insert into batch_execution(job_name,target_date,status,triggered_by,started_at,note) values ('initial-load',current_date-3,'RUNNING','GAP_FILL',now()-interval '3 days','rebut16 sim bootstrap 꼬리 GAP_FILL 유령') returning id" | head -1)
echo "=== K2 bootstrap 꼬리 GAP_FILL 유령(id $ID2 · 10-03 703→704 꼴)"; run ops/crons-done.sh; echo "rc=$?"
echo "=== K3 stop.sh --app(가짜 홈)"; run ops/stop.sh --app; echo "stop rc=$? · 가짜 앱: $(kill -0 $APP 2>/dev/null && echo 살아 있음 || echo 끝)"
echo "=== K4 어느 스윕이 이 행을 닫나(술어를 SELECT로 · BatchExecutionSweeper 글자)"
echo -n "웹(SCHEDULER·ADMIN + GAP_FILL∧indicator-origin-rewrite): "; q "select count(*) from batch_execution where status='RUNNING' and (triggered_by in ('SCHEDULER','ADMIN') or (triggered_by='GAP_FILL' and job_name in ('indicator-origin-rewrite'))) and id=$ID2"
echo -n "bootstrap(MANUAL): "; q "select count(*) from batch_execution where status='RUNNING' and triggered_by in ('MANUAL') and id=$ID2"
echo "=== K5 이 JVM 뒤에 시작된 행만 세는 대안 술어(started_at >= 앱 기동) — 같은 상태"; q "select count(*) from batch_execution where status='RUNNING' and triggered_by in ('SCHEDULER','ADMIN','GAP_FILL') and started_at >= now() - interval '1 minute'"
kill $APP 2>/dev/null; wait $APP 2>/dev/null; echo "가짜 앱 끔: $(kill -0 $APP 2>/dev/null && echo 아직 || echo 끝)"
# 내가 넣은 두 행은 내 시험 DB에 그대로 둔다(note에 rebut16 표지).
