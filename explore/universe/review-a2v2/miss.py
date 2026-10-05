import json, collections
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/'
sec=json.load(open(S+'sec-tickers.json')); fi={f:i for i,f in enumerate(sec['fields'])}
t2={}; ex={}
for r in sec['data']:
    t2[r[fi['ticker']]]=r[fi['cik']]; ex[r[fi['ticker']]]=r[fi['exchange']]
def g(d,s): return d.get(s, d.get(s.replace('.','-'),'ABSENT'))
al=json.load(open(S+'a2/out/assets-all-any.json'))
act={a['symbol'] for a in al if a['status']=='active' and a['exchange'] in ('NYSE','NASDAQ')}
st=collections.defaultdict(list)
for a in al: st[a['symbol']].append(a['status']+'/'+a['exchange'])
last=json.load(open('lastbar.json'))
pool=[l.rstrip('\n').split('\t') for l in open('pool.tsv')]
c=collections.Counter(); rows=[]
for p in pool:
    t=p[0]
    if t in act: continue
    e=g(ex,t); c[str(e)]+=1
    if e in ('NYSE','Nasdaq'): continue
    rows.append((t,p[1],str(e),','.join(st.get(t,['-'])),last.get(t,'?'),p[2],p[6][:45]))
print(c)
for r in rows: print('\t'.join(r))
