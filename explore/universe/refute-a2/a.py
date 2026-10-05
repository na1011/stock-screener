import json, collections
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/'
allA=json.load(open(S+'a2/out/assets-all-any.json'))
print('alpaca all', len(allA))
bysym=collections.defaultdict(list)
for a in allA: bysym[a['symbol']].append(a)
active={a['symbol'] for a in allA if a['status']=='active' and a['exchange'] in ('NYSE','NASDAQ')}
print('active NYSE/NASDAQ', len(active))
sec=json.load(open(S+'sec-tickers.json'))['data']
sec_t={r[2]:r for r in sec}
cik_of={r[2]:r[0] for r in sec}
tick_by_cik=collections.defaultdict(list)
for r in sec: tick_by_cik[r[0]].append(r[2])
stock={}
for l in open('stock-us.tsv'):
    p=l.rstrip('\n').split('\t')
    if len(p)<3: continue
    stock[p[0]]=p[1]
print('stock', len(stock))
# new listings = active - stock
new=sorted(active-set(stock))
print('active not in stock', len(new))
stock_ciks=collections.defaultdict(list)
for t in stock:
    # stock tickers use '.' for class; SEC uses '-'
    for cand in (t, t.replace('.','-')):
        if cand in cik_of: stock_ciks[cik_of[cand]].append(t)
ren=[]
for t in new:
    c=cik_of.get(t) or cik_of.get(t.replace('.','-'))
    if c and c in stock_ciks:
        ren.append((t,c,stock_ciks[c], sec_t.get(t,[None,None])[1]))
print('new tickers whose CIK is already in stock under other ticker:', len(ren))
for r in ren: print('  ',r)
# removed members: is ticker still in alpaca active?
print('--- removed members (observed) not in alpaca active now:')
for l in open('top-removed.tsv'):
    p=l.rstrip('\n').split('\t')
    if len(p)<4: continue
    t=p[0]
    if t not in active:
        c=cik_of.get(t) or cik_of.get(t.replace('.','-'))
        alts=[x for x in tick_by_cik.get(c,[]) if x!=t] if c else []
        st=[(a['status'],a['exchange']) for a in bysym.get(t,[])]
        print('  ',p, 'cik',c, 'sec_alts',alts, 'alpaca',st)
