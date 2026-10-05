import json, csv, collections
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/'
sec=json.load(open(S+'sec-tickers.json'))
fi={f:i for i,f in enumerate(sec['fields'])}
t2=collections.OrderedDict(); c2t=collections.defaultdict(list); exch={}
for r in sec['data']:
    t=r[fi['ticker']]; t2[t]=r[fi['cik']]; c2t[r[fi['cik']]].append(t); exch[t]=r[fi['exchange']]
def cik(sym):
    return t2.get(sym, t2.get(sym.replace('.','-')))
def sx(sym):
    return exch.get(sym, exch.get(sym.replace('.','-')))
al=json.load(open(S+'a2/out/assets-all-any.json'))
act={a['symbol']:a for a in al if a['status']=='active' and a['exchange'] in ('NYSE','NASDAQ')}
anyst=collections.defaultdict(list)
for a in al: anyst[a['symbol']].append((a['status'],a['exchange']))
activeCik=collections.defaultdict(list)
for s in act:
    c=cik(s)
    if c: activeCik[c].append(s)
pool=[l.rstrip('\n').split('\t') for l in open('pool.tsv')]
hidden=[]
for p in pool:
    t=p[0]
    if t in act: continue
    if sx(t) not in ('NYSE','Nasdaq'): continue
    hidden.append(p)
print('pool',len(pool),'hidden',len(hidden))
for p in hidden:
    t=p[0]; c=cik(t)
    sib=[s for s in activeCik.get(c,[]) if s!=t]
    print('\t'.join([t,p[1],p[4],str(c),','.join(sib) or '-', ','.join(x for x in c2t.get(c,[]) if x!=t.replace('.','-')), p[6][:40]]))
