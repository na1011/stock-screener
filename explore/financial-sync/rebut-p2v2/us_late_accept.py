# 제출일(filingDate)보다 뒤에 접수된(acceptanceDateTime) 정기 공시 — 그날 색인의 첫 판에 없었을 수 있는 공시 · 0콜 · 캐시 p1rs/cache-1006
import json, glob, collections
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad'
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
seen=set(); out=[]; tot=collections.Counter()
for p in glob.glob(S+'/p1rs/cache-1006/sec/submissions/CIK*.json'):
    j=json.load(open(p)); tab=j['filings']['recent'] if 'filings' in j else j
    for i,a in enumerate(tab['accessionNumber']):
        if a in seen: continue
        seen.add(a)
        f=tab['form'][i]; fd=tab['filingDate'][i]; acc=tab['acceptanceDateTime'][i]
        if f not in PER or fd < '2024-01-01': continue
        tot['periodic']+=1
        ad=acc[:10]; hm=acc[11:16]
        if ad > fd:
            tot['accepted_after_filing_date']+=1; out.append((fd, acc, f, a, p.split('/')[-1]))
        elif ad == fd and hm >= '22:00':
            tot['accepted_after_2200']+=1; out.append((fd, acc, f, a, p.split('/')[-1]))
print(dict(tot))
for r in sorted(out): print(*r, sep='\t')
