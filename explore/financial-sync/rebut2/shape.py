"""83 표본 · recent만 · filingDate>=2018-01-01 · isXBRL=1 · 정기 · companyfacts us-gaap 0 = 보충 대상 — 모양별 + 같은 모양의 companyfacts 있는 공시(제거 대조 후보) 수."""
import json,collections
G='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
rows=[l.rstrip('\n').split('\t') for l in open('../rebut1/t80.tsv') if not l.startswith('SUMMARY')]
shape=collections.Counter(); stocks=collections.defaultdict(set); ctrl=collections.Counter()
for t,cik in rows:
    r=json.load(open(G+f'subs-{cik}.json'))['filings']['recent']
    f=json.load(open(G+f'facts-{cik}.json'))['facts']
    tx=collections.defaultdict(set)
    for tax,cs in f.items():
        for c,node in cs.items():
            for u,arr in node['units'].items():
                for x in arr: tx[x['accn']].add(tax)
    for i,a in enumerate(r['accessionNumber']):
        fm=r['form'][i]
        if fm not in PER or r['isXBRL'][i]!=1 or r['filingDate'][i]<'2018-01-01': continue
        inl='inline' if r['isInlineXBRL'][i]==1 else 'plain'
        yr=r['filingDate'][i][:4]; era='2026' if yr=='2026' else ('2018-19' if yr<'2020' else '2020-25')
        if 'us-gaap' in tx[a]: ctrl[(fm,inl,era)]+=1; continue
        k=(fm,inl,era,'cf-dei' if 'dei' in tx[a] else ('cf-other' if tx[a] else 'cf-none'))
        shape[k]+=1; stocks[k].add(t)
for k,v in sorted(shape.items(), key=lambda kv:-kv[1]): print(k,v,sorted(stocks[k])[:6])
for k in sorted({k[:3] for k in shape}): print('ctrl',k,ctrl.get(k,0))
