#!/bin/bash
# A2 RA2 — R0과 같은 9날짜 쓰기 없는 재생을 같은 사본(sc_a)에서 jar 둘로: a = main(A만) · h = wt-a2(A + A2).
set -uo pipefail
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
for side in a h; do
  [ $side = a ] && R=/Users/na1011/dev/stock-screener || R=$S/wt-a2
  bash $S/cikdry/boot2.sh $R stock_screener_sc_a --job=universe-restore --universe=US_TOP1000 --dates=2026-03-03,2026-03-05,2026-08-31,2026-09-04,2026-09-07,2026-09-11,2026-09-18,2026-09-25,2026-10-02 > $S/cikdry/ra2-$side.log 2>&1
  echo "$side rc=$?"
done
