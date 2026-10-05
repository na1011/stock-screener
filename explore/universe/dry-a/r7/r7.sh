#!/bin/bash
# R7 — 14:00 사슬(기업 행위 수집 → 재구축 → 화해)을 새 대표(c) · 옛 대표(a)에 티커로 좁혀 돌리고 원장·지표를 전후로 센다.
set -uo pipefail
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/cikdry
P=/opt/homebrew/opt/postgresql@16/bin/psql
NEW=BTSG,NWSA,UHAL.B,BF.B,MKC,MOG.A,PPL,RUSHA,TAP,WSO,Z
OLD=BTSGU,NWS,UHAL,BF.A,MKC.V,MOG.B,PPLC,RUSHB,TAP.A,WSO.B,ZG
snap() { # $1 db $2 tickers $3 out
  $P -d $1 -At -F$'\t' -c "SELECT s.ticker, (SELECT count(*) FROM corporate_action ca WHERE ca.stock_id=s.id) ca,
     (SELECT string_agg(ca.type||':'||ca.effective_date||':'||ca.status||':'||coalesce(ca.ratio::text,''), ' ' ORDER BY ca.effective_date) FROM corporate_action ca WHERE ca.stock_id=s.id) ev,
     (SELECT count(*) FROM indicator_value iv WHERE iv.stock_id=s.id) iv,
     (SELECT md5(string_agg(p.trade_date||':'||p.adj_close, ',' ORDER BY p.trade_date)) FROM price_daily p WHERE p.stock_id=s.id) adj
     FROM stock s WHERE s.market_code='US' AND s.ticker = ANY(string_to_array('$2', ',')) ORDER BY 1" > $3
}
for side in c a; do
  db=stock_screener_sc_$side; [ $side = c ] && T=$NEW || T=$OLD
  snap $db $T $S/r7-$side-before.tsv
  for job in corporate-action adjustment-rebuild indicator-reconcile; do
    bash $S/boot.sh $side --job=$job --tickers=$T > $S/r7-$side-$job.log 2>&1; rc=$?
    echo "$side $job rc=$rc"; [ $rc -ne 0 ] && exit $rc
  done
  snap $db $T $S/r7-$side-after.tsv
done
echo done
