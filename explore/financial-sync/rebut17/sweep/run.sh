#!/bin/bash
# rebut17 — 보충 성공 경로 바이트 동일: 같은 날 캐시(cache-1006 · 0콜)로 SecEdgarFactsSweepTest 보충 켬(A)을 v11·v12 사본에서 돌려 diff
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
C=$S/p1rs/cache-1006; O=$S/wt-explore/explore/financial-sync/rebut17/sweep
for v in v12 v11; do
  (cd $S/r17/$v && env REPLAY_SWEEP_DIR=$C/companyfacts REPLAY_SWEEP_OUT=$O/A-$v.tsv REPLAY_SWEEP_FROM=2018 \
     REPLAY_SWEEP_EXTRA=$O/extra-A-$v.tsv REPLAY_SUPPLEMENT_DIR=$C TEST_DB_URL=jdbc:postgresql://localhost:5432/stock_screener_r17a_test \
     ./gradlew test -PskipFrontend --no-daemon --tests "*SecEdgarFactsSweepTest*" --rerun > $O/A-$v.log 2>&1)
  echo "$v rc=$?" >> $O/rc.txt
  python3 - "$S/r17/$v" "$v" <<'PY' >> $O/rc.txt
import glob, sys, xml.etree.ElementTree as ET
x = glob.glob(sys.argv[1] + '/build/test-results/test/TEST-*SecEdgarFactsSweepTest.xml')[0]
r = ET.parse(x).getroot(); out = r.find('system-out').text or ''
print(sys.argv[2], 'tests=' + r.get('tests'), 'fail=' + r.get('failures'), 'err=' + r.get('errors'), [l for l in out.splitlines() if l.startswith('SWEEP')][:4])
PY
done
echo DONE >> $O/rc.txt
