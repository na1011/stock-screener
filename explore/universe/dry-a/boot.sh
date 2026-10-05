#!/bin/bash
# CIK 대표 드라이런 — 사본에서만. 사용: boot.sh <a|c> <잡 인자...>
# a = main 작업 트리(지금 main 코드) · c = wt-cik(main + 어댑터 변경). 알림 변수 비움 · DB_URL과 BOOTSTRAP_DB 둘 다.
set -uo pipefail
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
side=$1; shift
case $side in
  a) REPO=/Users/na1011/dev/stock-screener; DB=stock_screener_sc_a ;;
  c) REPO=$S/wt-cik; DB=stock_screener_sc_c ;;
  *) echo "a|c"; exit 2 ;;
esac
cd $REPO && DB_URL="jdbc:postgresql://localhost:5432/$DB" BOOTSTRAP_DB="$DB" ALERT_WEBHOOK_URL= MAIL_USERNAME= MAIL_PASSWORD= SENTRY_DSN= \
  ./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap --server.port=0 $*"
