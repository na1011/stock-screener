#!/bin/bash
# 사용: run.sh <코드 사본> <이름> — ReaderVersionPinTest + R17PmBehaviorTest를 돌려 결과를 rebut17/pin/<이름>.*에 적는다
C=$1; N=$2; R=$(dirname "$0")
(cd $C && TEST_DB_URL=jdbc:postgresql://localhost:5432/stock_screener_r17a_test R16_PM_OUT=$R/$N-behavior.txt ./gradlew test -PskipFrontend --no-daemon --tests '*ReaderVersionPinTest' --tests '*R17PmBehaviorTest' > $R/$N.log 2>&1); echo "== $N gradle rc=$?"
python3 - "$C" <<'PY'
import glob,sys,xml.etree.ElementTree as ET
for f in sorted(glob.glob(sys.argv[1]+'/build/test-results/test/TEST-*.xml')):
    r=ET.parse(f).getroot(); print(' ', r.get('name').split('.')[-1], 'tests',r.get('tests'),'fail',r.get('failures'),'err',r.get('errors'),'skip',r.get('skipped'))
    for tc in r.findall('testcase'):
        for fl in tc.findall('failure')+tc.findall('error'):
            m=(fl.get('message') or '').replace('\n',' | ')
            print('  RED', tc.get('name')[:60], '::', m[:300])
PY
cat $R/$N-behavior.txt 2>/dev/null | grep folder
