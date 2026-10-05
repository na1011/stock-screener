import json, collections
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/'
allA=json.load(open(S+'a2/out/assets-all-any.json'))
active={a['symbol'] for a in allA if a['status']=='active' and a['exchange'] in ('NYSE','NASDAQ')}
sec=json.load(open(S+'sec-tickers.json'))['data']
cik_of={r[2]:r[0] for r in sec}
stock={l.split('\t')[0] for l in open('stock-us.tsv') if '\t' in l}
new=sorted(active-stock)
fr={}
for f in ('frames-CY2026Q1I.json','frames-CY2026Q2I.json'):
    d=json.load(open(S+'a2/out/'+f))
    for r in d['data']: fr[r['cik']]=(r['val'],r['end'])
bars=json.load(open(S+'a2/out-new/bars-0-0.json'))['bars']
rows=[]
for t in new:
    b=bars.get(t,[])
    if not b: rows.append((0,t,'nobars',cik_of.get(t)));continue
    c=b[-1]['c']; dv=sum(x['c']*x['v'] for x in b)
    ck=cik_of.get(t) or cik_of.get(t.replace('.','-'))
    sh=fr.get(ck)
    cap=sh[0]*c/1e9 if sh else None
    rows.append((cap or 0,t,c,round(dv/1e6,1),len(b),ck,sh))
rows.sort(key=lambda r:-r[0])
for r in rows[:15]: print(r)
print('with frames shares:',sum(1 for r in rows if r[0]))
