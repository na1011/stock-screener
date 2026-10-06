#!/bin/bash
# 배포 순서 모의: 지금 기본 시험 DB의 복제(r18d · V62 + main 빌드 잔여 행)에 0부 트리 → 1부 트리를 차례로 clean build
R=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/wt-explore/explore/financial-sync/refute18
export TEST_DB_URL=jdbc:postgresql://localhost:5432/stock_screener_r18d_test
for t in seq0 seq1; do
  cp -R /Users/na1011/dev/stock-screener/frontend/node_modules $R/$t/frontend/
  date +%T > $R/$t-full.start
  (cd $R/$t && ./gradlew --no-daemon clean build -x frontendInstall) > $R/$t-full.log 2>&1
  echo "rc=$?" >> $R/$t-full.log; date +%T >> $R/$t-full.log
  psql -X -At -d stock_screener_r18d_test -c "select '$t', string_agg(version,',' order by installed_rank) from flyway_schema_history where version::int>=62" >> $R/seq.out
done
