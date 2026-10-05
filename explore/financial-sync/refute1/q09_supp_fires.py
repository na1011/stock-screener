# 보충 술어(isXBRL=1 ∧ 그 accn의 us-gaap 팩트 0 · reportDate>=2018) 가 83 CIK(recent)에서 집는 공시 — 6월 분기 결손 밖
import json, collections
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
P={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
t2c={}
for l in open('../../financial-gap/refute1/t2cik.tsv'):
    t,c=l.split(); t2c[t]=c
t2c.update({'AAPL':'320193','MSFT':'789019','JPM':'19617'})
fires=collections.defaultdict(list)
for t,c in t2c.items():
    s=json.load(open(S+f'subs-{c}.json'))['filings']['recent']
    f=json.load(open(S+f'facts-{c}.json'))['facts']
    us={x['accn'] for v in f.get('us-gaap',{}).values() for a in v['units'].values() for x in a}
    for i,a in enumerate(s['accessionNumber']):
        if s['form'][i] in P and s['isXBRL'][i]==1 and (s['reportDate'][i] or '')>='2018-01-01' and a not in us:
            fires[t].append((s['form'][i],s['filingDate'][i]))
mid=lambda x: x[0] in('10-Q','10-K') and x[1]>='2026-07-01'
beyond={t:[x for x in v if not mid(x)] for t,v in fires.items()}
print('tickers where supplement fires:',len(fires),' beyond the 2026 mid-year filing:',sum(1 for v in beyond.values() if v), 'filings', sum(len(v) for v in beyond.values()))
print(collections.Counter(x[0] for v in beyond.values() for x in v))
for t,v in sorted(beyond.items()):
    if v: print(t,v)
