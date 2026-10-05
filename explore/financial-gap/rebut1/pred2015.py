import json, glob, collections
O='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
rows=[]
oldest_recent=[]
for sf in sorted(glob.glob(O+'subs-*.json')):
    cik=sf.split('-')[-1][:-5]
    s=json.load(open(sf)); r=s['filings']['recent']
    oldest_recent.append(min(r['filingDate']))
    try: cf=json.load(open(O+f'facts-{cik}.json'))
    except FileNotFoundError: continue
    byacc=collections.Counter(); gaap=collections.Counter(); flow=collections.Counter()
    for tax,cs in cf['facts'].items():
        for c,b in cs.items():
            for u,arr in b['units'].items():
                for n in arr:
                    byacc[n['accn']]+=1
                    if tax=='us-gaap':
                        gaap[n['accn']]+=1
                        if n.get('start'): flow[n['accn']]+=1
    for i,acc in enumerate(r['accessionNumber']):
        f=r['form'][i]
        if f not in PER: continue
        fd=r['filingDate'][i]
        if fd<'2015-01-01': continue
        rows.append((cik,acc,f,fd,r['reportDate'][i],r['isXBRL'][i],r['isInlineXBRL'][i],r['isXBRLNumeric'][i],byacc[acc],gaap[acc],flow[acc]))
print('periodic filings since 2018 in recent:',len(rows),' oldest recent filingDate min/max over ciks', min(oldest_recent), max(oldest_recent))
z=[x for x in rows if x[8]==0]
print('zero companyfacts facts:',len(z))
print(' by year', collections.Counter(x[3][:4] for x in z))
print(' by form', collections.Counter(x[2] for x in z))
print(' by isXBRL/inline/numeric', collections.Counter((x[5],x[6],x[7]) for x in z))
for x in z:
    if x[3]<'2026-07-01': print('  ',x)
p=[x for x in rows if x[8]>0 and x[9]==0]
print('has facts but zero us-gaap:',len(p), collections.Counter((x[2],x[5],x[7]) for x in p))
for x in p[:15]: print('  ',x)
q=[x for x in rows if x[9]>0 and x[10]==0]
print('us-gaap but zero flow:',len(q)); 
for x in q[:10]: print('  ',x)
small=[x for x in rows if 0<x[9]<30]
print('us-gaap 1..29:',len(small))
for x in small[:20]: print('  ',x)
