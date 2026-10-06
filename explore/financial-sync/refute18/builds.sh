#!/bin/bash
# 순차 빌드 — 전용 시험 DB stock_screener_r18b_test만 · --no-daemon
R=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/wt-explore/explore/financial-sync/refute18
export TEST_DB_URL=jdbc:postgresql://localhost:5432/stock_screener_r18b_test
for t in p1only sim; do
  cp -R /Users/na1011/dev/stock-screener/frontend/node_modules $R/$t/frontend/ 2>/dev/null
  date +%T > $R/$t-full.start
  (cd $R/$t && ./gradlew --no-daemon clean build -x frontendInstall) > $R/$t-full.log 2>&1
  echo "rc=$?" >> $R/$t-full.log
  date +%T >> $R/$t-full.log
done
