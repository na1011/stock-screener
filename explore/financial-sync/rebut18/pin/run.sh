#!/bin/bash
# 사용: run.sh <사본 디렉터리> <이름> [시험 패턴]  — 전용 시험 DB r18a로만 돈다. 결과 요약과 붉은 해시를 남긴다.
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
R=$S/wt-explore/explore/financial-sync/rebut18/pin
C=$1; N=$2; P=${3:-*ReaderVersionPinTest}
rm -rf $C/build/test-results/test
(cd $C && TEST_DB_URL=jdbc:postgresql://localhost:5432/stock_screener_r18a_test ./gradlew test -PskipFrontend --no-daemon --tests "$P" > $R/$N.log 2>&1); rc=$?
echo "== $N gradle rc=$rc" | tee $R/$N.sum
python3 - "$C" "$N" "$R" <<'PY' | tee -a $R/$N.sum
import glob,sys,re,xml.etree.ElementTree as ET
C,N,R=sys.argv[1:4]
fs=glob.glob(C+'/build/test-results/test/TEST-*.xml')
if not fs: print('  NO RESULTS')
for f in fs:
    r=ET.parse(f).getroot(); print(' ',r.get('name').split('.')[-1],'tests',r.get('tests'),'fail',r.get('failures'),'err',r.get('errors'),'skip',r.get('skipped'))
    for tc in r.findall('testcase'):
        for fl in tc.findall('failure')+tc.findall('error'):
            m=(fl.get('message') or ''); print('  RED', tc.get('name')[:50], '::', m.replace('\n',' | ')[:300])
            if '판 범위의 소스가 바뀌었다' in m:
                h=re.findall(r'but was:\s*"([0-9a-f]{64})"', m) or re.findall(r'Actual\s*:\s*"?([0-9a-f]{64})', m) or re.findall(r'"([0-9a-f]{64})"', m)
                if h: open(R+'/hash-'+N,'w').write(h[-1]); print('  HASH',h[-1])
PY
