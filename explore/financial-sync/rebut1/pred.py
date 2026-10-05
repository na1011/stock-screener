"""공시 갈래 술어 「submissions 정기 접수번호(isXBRL==1 · 창 안) ∉ DB report_code」를 83종목 저장 원자료로 재현."""
import json, collections, sys
G='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
FROM=sys.argv[1] if len(sys.argv)>1 else '2018-01-01'
rows=[l.rstrip('\n').split('\t') for l in open('t80.tsv') if not l.startswith('SUMMARY')]
db=collections.defaultdict(set)
for l in open('db83.tsv'):
    t,rc,*_=l.rstrip('\n').split('\t')
    if rc and ':' in rc: db[t].add(rc.split(':',1)[1])
out=open(f'pred-{FROM}.tsv','w')
tot=collections.Counter(); per_stock=collections.Counter()
for t,cik in rows:
    s=json.load(open(G+f'subs-{cik}.json'))['filings']['recent']
    f=json.load(open(G+f'facts-{cik}.json'))['facts']
    acc_tax=collections.defaultdict(set)
    for tax,concepts in f.items():
        for c,node in concepts.items():
            for u,arr in node.get('units',{}).items():
                for x in arr: acc_tax[x['accn']].add(tax)
    cover=min(s['filingDate'])
    for i,a in enumerate(s['accessionNumber']):
        fm=s['form'][i]; fd=s['filingDate'][i]
        if fm not in PER or fd<FROM: continue
        if s['isXBRL'][i]!=1: continue
        if a in db[t]: tot['match']+=1; continue
        taxes=acc_tax.get(a,set())
        cls='usgaap>0' if 'us-gaap' in taxes else ('dei-only' if taxes=={'dei'} else ('none' if not taxes else 'other:'+'/'.join(sorted(taxes))))
        tot[cls]+=1; per_stock[(t,cls)]+=1
        out.write(f"{t}\t{cik}\t{fm}\t{fd}\t{s['reportDate'][i]}\t{a}\t{cls}\t{s['primaryDocDescription'][i]}\t{s['size'][i]}\n")
print(FROM, dict(tot))
stocks=collections.defaultdict(set)
for (t,c),n in per_stock.items(): stocks[c].add(t)
print({c:len(v) for c,v in stocks.items()})
allun=set(t for (t,c) in per_stock)
print('stocks with any unmatched:',len(allun),'of',len(rows))
