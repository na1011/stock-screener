#!/bin/bash
# 이른 판 켬/끔 — 사본 a 한 곳에서 차례로(끔 → 켬). 규칙은 CIK-REP-PLAN.md 「B 이른 판 측정 규칙」.
set -uo pipefail
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/cikdry; P=/opt/homebrew/opt/postgresql@16/bin/psql; DB=stock_screener_sc_a; T=ACMR,AGX,AXTI,BB,CAKE,FTDR,GRAL,GVA,IOVA,KLIC,KOD,LAUR,MOG.B,MTRN,NAVN,NJR,NOVT,PSMT,TFX,TTAN,VFC,VSH,WH
snap() { $P -d $DB -At -F$'\t' -c "SELECT s.ticker, iv.indicator_code, iv.trade_date, iv.value FROM indicator_value iv JOIN stock s ON s.id=iv.stock_id WHERE s.market_code='US' AND s.ticker = ANY(string_to_array('$T', ',')) ORDER BY 1,2,3" > $1; wc -l < $1; }
fin() { $P -d $DB -At -c "SELECT count(*), count(*) FILTER (WHERE is_latest) FROM financial_statement f JOIN stock s ON s.id=f.stock_id WHERE s.market_code='US' AND s.ticker = ANY(string_to_array('$T', ','))"; }
echo "before rows=$(snap $S/early-before.tsv) fin=$(fin)"
run() { bash $S/boot.sh a "$@" > $S/early-$LOG.log 2>&1; rc=$?; echo "$LOG rc=$rc"; [ $rc -ne 0 ] && exit $rc; }
LOG=fr-off run --job=financial-refill --tickers=$T
LOG=rec-off run --job=indicator-reconcile --tickers=$T
echo "off rows=$(snap $S/early-off.tsv) fin=$(fin)"
LOG=fr-on run --job=financial-refill --tickers=$T --insert-earlier-vintages=true
LOG=rec-on run --job=indicator-reconcile --tickers=$T
echo "on rows=$(snap $S/early-on.tsv) fin=$(fin)"
grep -h "재무 결손 보정을 마쳤다" $S/early-fr-off.log $S/early-fr-on.log | sed -E 's/.*마쳤다\. //' | cut -c1-200
