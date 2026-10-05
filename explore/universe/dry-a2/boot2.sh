#!/bin/bash
# 드라이런 기동 — 저장소와 사본을 따로 고른다. 사용: boot2.sh <repo> <db> <잡 인자...>
# 사본 이름만 받는다(운영 DB에 붙는 오타를 여기서 막는다) · 알림 변수 비움 · DB_URL과 BOOTSTRAP_DB 둘 다.
set -uo pipefail
REPO=$1; DB=$2; shift 2
case $DB in stock_screener_sc_*|stock_screener_b_*) ;; *) echo "사본 이름이 아니다: $DB"; exit 2 ;; esac
cd $REPO && DB_URL="jdbc:postgresql://localhost:5432/$DB" BOOTSTRAP_DB="$DB" ALERT_WEBHOOK_URL= MAIL_USERNAME= MAIL_PASSWORD= SENTRY_DSN= \
  ./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap --server.port=0 $*"
