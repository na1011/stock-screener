#!/bin/bash
source "$(dirname "$0")/env.sh"
t0=$(date +%s)
(cd $REPO && DB_NAME=$PROD ops/dump-db.sh) > $A/dump.log 2>&1 || { tail -5 $A/dump.log; die "덤프 실패"; }
DUMP=$(sed -n 's/^\[dump\] [^ ]* → //p' $A/dump.log | head -1)
[ -f "$DUMP" ] || die "덤프 경로를 못 찾았다: '$DUMP'"; [ "$(stat -f %m "$DUMP")" -ge "$t0" ] || die "덤프가 이번 실행 것이 아니다"
echo "$DUMP" > $A/dump-path.txt
for db in $S1 $S0; do
  [ -z "$(q postgres "select 1 from pg_database where datname='$db'")" ] || die "$db가 이미 있다 — 지우는 것은 사용자 확인 뒤"
  $PGBIN/createdb "$db"
  PGOPTIONS='-c statement_timeout=0' $PGBIN/pg_restore -d "$db" -j4 --no-owner "$DUMP" || die "$db 복원 실패"
  PGOPTIONS='-c statement_timeout=0' command psql -d "$db" -v ON_ERROR_STOP=1 -c 'VACUUM (ANALYZE)' > /dev/null
done
for db in $PROD $S1 $S0; do echo "$db US재무=$(q $db "select count(*) from financial_statement f join stock s on s.id=f.stock_id where s.market_code='US'")"; done
