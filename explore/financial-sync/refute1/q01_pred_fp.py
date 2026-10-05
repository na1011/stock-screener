# (나) 미국 공시 갈래 술어의 거짓 양성: submissions recent 정기공시(isXBRL=1 · reportDate>=2018-01-01) 접수번호 ∉ DB report_code
import json, collections, sys
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
P={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
t2c={}
for l in open('../../financial-gap/refute1/t2cik.tsv'):
    t,c=l.split(); t2c[t]=c
t2c.update({'AAPL':'320193','MSFT':'789019','JPM':'19617'})
db=collections.defaultdict(set)
for l in open('db_accn.tsv'):
    t,a=l.rstrip('\n').split('\t'); db[t].add(a)
gap=set(l.split('\t')[0] for l in open('../../financial-gap/gap80.tsv') if not l.startswith('ticker'))
tot=collections.Counter(); miss=collections.Counter(); per={}
for t,c in sorted(t2c.items()):
    try: d=json.load(open(S+f'subs-{c}.json'))
    except FileNotFoundError: print('nofile',t,c); continue
    r=d['filings']['recent']; m=[]
    for i,a in enumerate(r['accessionNumber']):
        f=r['form'][i]
        if f in P and r['isXBRL'][i]==1 and (r['reportDate'][i] or '')>='2018-01-01':
            tot[f]+=1
            if a not in db[t]:
                miss[f]+=1; m.append((f,r['filingDate'][i],r['reportDate'][i],a))
    per[t]=m
print('tot',dict(tot)); print('miss',dict(miss))
ctrl=[t for t in per if t in('AAPL','MSFT','JPM')]
for t in ctrl: print('CTRL',t,per[t])
# 6월 10-Q 하나만 빠진 꼴을 빼고 남는 것
extra={t:[x for x in m if not (x[0] in('10-Q','10-K') and x[2]>='2026-05-01')] for t,m in per.items()}
n=sum(1 for t,m in extra.items() if m); print('tickers with misses beyond the 2026 mid-year filing:',n,'of',len(per))
for t,m in sorted(extra.items()):
    if m: print(t, len(m), m[:4])
