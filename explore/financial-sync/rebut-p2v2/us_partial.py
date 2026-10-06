# companyfacts에 us-gaap로 실렸지만 우리 매핑 개념이 0인 정기 공시(현 편입 · 2025-01-01 이후 제출) — 0콜, 캐시 p1rs/cache-1006
import json, os, re, sys, collections
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad'
CF=S+'/p1rs/cache-1006/companyfacts'
col=open('/Users/na1011/dev/stock-screener/src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java').read()
MAPPED=set(re.findall(r'"([A-Z][A-Za-z]+)"', col))
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
members=[l.split('\t')[1].strip() for l in open(S+'/wt-explore/explore/financial-sync/rebut-p2v1/us_members.tsv') if '\t' in l]
tot=collections.Counter(); rows=[]
missing=0
for t in members:
    p=os.path.join(CF, t+'.json')
    if not os.path.exists(p): missing+=1; continue
    d=json.load(open(p))
    acc=collections.defaultdict(lambda: [set(), None, None])
    for c, node in d.get('facts',{}).get('us-gaap',{}).items():
        for u, arr in node.get('units',{}).items():
            for f in arr:
                if f.get('form') in PER and f.get('filed','')>='2025-01-01':
                    a=acc[f['accn']]; a[0].add(c); a[1]=f['form']; a[2]=f['filed']
    for accn,(cs,form,filed) in acc.items():
        tot['filings_usgaap']+=1
        if not (cs & MAPPED):
            tot['zero_mapped']+=1; rows.append((t,accn,form,filed,len(cs),sorted(cs)[:6]))
print('members',len(members),'no cache',missing, dict(tot))
for r in sorted(rows, key=lambda r:r[3]): print(*r, sep='\t')
