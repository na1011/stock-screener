#!/bin/bash
# 사용: run.sh <시나리오 이름> <사본 cl|clold> <고장 JSON 문자열>
# 가짜 서버(내가 띄움 · 끝에 내가 끈다)에 수집기를 돌린다 — 외부 0콜. 출력은 coll/out/<이름>.
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
R=$S/wt-explore/explore/financial-sync/rebut18/coll
N=$1; C=$S/r18/$2; F="$3"; PORT=18731
mkdir -p $R/out/$N
echo "$F" > $R/out/$N.faults.json
: > $R/out/$N.requests.tsv
python3 $R/mock.py $PORT $S/p1v14-cache/out $R/out/$N.faults.json $R/out/$N.requests.tsv &
MOCK=$!
sleep_until_up() { for i in $(seq 1 50); do curl -s -o /dev/null http://127.0.0.1:$PORT/files/company_tickers_exchange.json && return 0; python3 -c 'import time; time.sleep(0.1)'; done; return 1; }
sleep_until_up || { echo "가짜 서버가 안 떴다"; kill $MOCK; exit 1; }
: > $R/out/$N.requests.tsv
printf 'AAPL\nABT\n' > $R/out/$N.tickers.txt
(cd $C && TEST_DB_URL=jdbc:postgresql://localhost:5432/stock_screener_r18a_test SUPP_CACHE_DIR=$R/out/$N SUPP_TICKERS=$R/out/$N.tickers.txt SUPP_FROM=2018 \
  SEC_EDGAR_USER_AGENT="rebut18 test@example.com" SUPP_DATA_BASE=http://127.0.0.1:$PORT SUPP_FILES_BASE=http://127.0.0.1:$PORT \
  ./gradlew liveTest -PskipFrontend --no-daemon --tests '*SecFilingSupplementCacheLiveTest*' -i > $R/out/$N.log 2>&1); rc=$?
kill $MOCK; wait $MOCK 2>/dev/null
echo "== $N rc=$rc · $(grep -a -o 'supplement.cache tickers=.*' $R/out/$N.log | head -1)"
grep -a 'supplement.cache failed' $R/out/$N.log | sed 's/^/   /' | cut -c1-220
echo "   요청 수 $(wc -l < $R/out/$N.requests.tsv) · 경로별 최대 시도 $(cut -f2 $R/out/$N.requests.tsv | sort -n | tail -1)"
