#!/bin/bash
# 사용: 00b-stop.sh   — 쓰기 세션 밖에서 돈다(ops/*.sh를 세션 안에서 exec하지 않는다 · write-session.sh 머리말).
# 관문 순서 10의 첫머리 「VACUUM → stop --app」. 앱이 떠 있으면 꺼진 jar의 크론(13:30 · 14:16 · 14:20)이 관문 도중에 옛 규칙으로 쓴다.
# stop.sh --app이 crons-done.sh로 「남은 발화가 없는가」를 묻고, 남았으면 멈춘다 — 기다렸다 다시 친다(--force는 사람의 판단).
source "$(dirname "$0")/env.sh"
[ -z "${SS_WRITE_SESSION:-}" ] || die "쓰기 세션 안이다 — 00b는 세션 밖에서 돈다(begin은 이 뒤)"
if [ "$PROD" = "$LIVE" ]; then
  PGOPTIONS='-c statement_timeout=0' command psql -d "$PROD" -v ON_ERROR_STOP=1 -c 'VACUUM (ANALYZE) indicator_value, financial_statement'
  "$REPO/ops/stop.sh" --app
fi
app_down "$PROD"   # 작은 DB면 운영 앱과 무관하다 — 대조군은 GATE_REQUIRE_APP_DOWN=1로 태운다
r=$(q "$PROD" "select count(*) from batch_execution where status='RUNNING'"); [ "$r" = 0 ] || die "앱을 내린 뒤 RUNNING $r"
echo "OK 앱 내림 — 다음: ops/write-session.sh begin <id>$( [ "$PROD" = "$LIVE" ] || echo " --db $PROD")"
