import json, collections, glob
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/'
allA=json.load(open(S+'a2/out/assets-all-any.json'))
bysym=collections.defaultdict(list)
for a in allA: bysym[a['symbol']].append((a['status'],a['exchange']))
active={a['symbol'] for a in allA if a['status']=='active' and a['exchange'] in ('NYSE','NASDAQ')}
sec=json.load(open(S+'sec-tickers.json'))['data']
def norm(t): return t.replace('-','.')
secmap={norm(r[2]):r for r in sec}
ciks=collections.defaultdict(list)
for r in sec: ciks[r[0]].append(norm(r[2]))
sh={}
for l in open(S+'a2/us-stock-sh.tsv'):
    p=l.rstrip('\n').split('\t'); sh[p[0]]=(p[1],p[2])
B={}
for f in glob.glob(S+'a2/out/bars-*.json'): B.update(json.load(open(f))['bars'])
hid=[t for t in sh if t in secmap and secmap[t][3] in ('NYSE','Nasdaq') and t not in active]
print(len(hid), 'with shares', sum(1 for t in hid if sh[t][1]=='t'))
for t in sorted(hid):
    r=secmap[t]; sib=[x for x in ciks[r[0]] if x!=t]
    b=B.get(t) or B.get(t.replace('.','-'))
    lb=(b[-1]['t'][:10],len(b)) if b else None
    print(t, sh[t][1], r[0], r[3], 'alpaca',bysym.get(t), 'sib',[(s, s in active, sh.get(s,('-','-'))[1]) for s in sib], 'bars',lb)
