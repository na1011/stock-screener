# 0콜: 83 CIK 캐시(ga/out2)에서 8-K가 보충 술어(isXBRL=1 ∧ us-gaap 0 · filingDate>=2018)에 걸리는 수 —
# P2가 8-K를 PERIODIC_FORMS에 더하고 보충 목록이 그 집합을 공유하면 생길 대상
import json, glob, collections, os
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
P={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
c8=collections.Counter(); per=collections.Counter(); ciks=0
for sf in sorted(glob.glob(S+'subs-*.json')):
    c=os.path.basename(sf)[5:-5]
    s=json.load(open(sf))['filings']['recent']
    f=json.load(open(S+f'facts-{c}.json'))['facts']
    us={x['accn'] for v in f.get('us-gaap',{}).values() for a in v['units'].values() for x in a}
    ciks+=1
    for i,a in enumerate(s['accessionNumber']):
        if s['filingDate'][i] < '2018-01-01': continue
        fm=s['form'][i]
        if fm in ('8-K','8-K/A'):
            c8['8k_total']+=1
            if s['isXBRL'][i]==1:
                c8['8k_xbrl']+=1
                if a not in us: c8['8k_xbrl_usgaap0']+=1; per[c]+=1
                else: c8['8k_xbrl_usgaap_present']+=1
        elif fm in P:
            c8['periodic_total']+=1
            if s['isXBRL'][i]==1 and a not in us: c8['periodic_fires']+=1
print('ciks',ciks, dict(c8))
print('8-K fires per CIK: median', sorted(per.values())[len(per)//2] if per else 0, 'max', max(per.values()) if per else 0)
