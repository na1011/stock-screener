#!/bin/bash
# 변이마다 판 고정 시험(두 해시를 따로) + 보충 시험 묶음. 사본에서만. 사용: run-mutants.sh base|<tag>...
R=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/wt-explore/explore/financial-sync/rebut12
cd /private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/r12copy
export TEST_DB_URL=jdbc:postgresql://localhost:5432/stock_screener_p1v4_test
for tag in "$@"; do
  if [ "$tag" = base ]; then python3 $R/mutate.py restore >/dev/null; else python3 $R/mutate.py $tag >/dev/null || { echo "$tag APPLY-FAIL"; continue; }; fi
  rm -rf build/test-results/test
  ./gradlew test -PskipFrontend --offline -q --tests "*ReaderVersionPinTest" --tests "*XbrlInstanceReaderTest" --tests "*SecFilingSupplementTest" \
    --tests "*SecFilingSupplementWriteContractTest" --tests "*SecEdgarFinancialAdapterTest" > $R/run-$tag.log 2>&1
  rc=$?
  python3 - "$tag" "$rc" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
tag, rc = sys.argv[1:]
tot = fail = 0; pin = {}
for x in glob.glob('build/test-results/test/TEST-*.xml'):
    r = ET.parse(x).getroot()
    for tc in r.iter('testcase'):
        tot += 1
        bad = tc.find('failure') is not None or tc.find('error') is not None
        fail += bad
        if 'ReaderVersionPinTest' in tc.get('classname'):
            pin[tc.get('name').split('(')[0]] = 'RED' if bad else 'green'
print(tag, 'rc=' + rc, 'tests=%d fail=%d' % (tot, fail), 'pin:', ' '.join('%s=%s' % kv for kv in sorted(pin.items())), flush=True)
PY
done
python3 $R/mutate.py restore >/dev/null
