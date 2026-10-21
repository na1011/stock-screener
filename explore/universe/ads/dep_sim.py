import sys,json,re
S=sys.argv[1]; px=json.load(open(f'{S}/cand_px.json')); rows=json.load(open(f'{S}/join.json'))
K={r['t']:(r['k'],r['src']) for r in rows if r['ownADS'] or r['t']=='MREO'}
W="PREFERRED|PFD|WARRANTS?|RIGHTS?|DEPOSITARY|NOTES?|DEBENTURES?|BONDS?|ETFS?|FUNDS?"
NC=re.compile(r'\b('+W+r')\b|\bCORPORATE UNITS?\b|\bEQUITY UNITS?\b|\bPREFERENCE\b|\bCAPITAL SECURITIES\b')
mode=sys.argv[2]  # 'off' = today's rule, 'ads' = ADS admitted with ratio
c=[]
for l in open(f'{S}/cand.tsv'):
    t,cik,sh,bs,bm,rc,mem,name=l.rstrip('\n').split('\t')
    b=px['bars'].get(t)
    if not b: continue
    nm=px['listed'].get(t, name)
    cut=bool(NC.search(nm.upper()))
    sh=float(sh)
    if t in K:
        k=K[t][0]
        if mode=='ads':
            cut=bool(NC.search(re.sub(r'AMERICAN DEPOSIT[AO]RY','ADS',nm.upper())))
            if not k: continue      # 비율 모름 → 미표시
            sh=sh/k
    if cut: continue
    c.append((sh*b[0],b[1],t,cik or t,mem=='t'))
best={}
for cap,tv,t,cik,mem in c:
    if cik not in best or tv>best[cik][1]: best[cik]=(cap,tv,t,mem)
rank=sorted(best.values(),reverse=True)[:1000]
top={x[2] for x in rank}
mem={l.split('\t')[0] for l in open(f'{S}/cand.tsv') if l.split('\t')[6]=='t'}
print(mode,'sim top',len(top),'∩members',len(top&mem),'boundary %.2fB'%(rank[-1][0]/1e9))
print(' new vs members:',sorted(top-mem)[:60])
print(' ADS in top:',sorted((x[2],round(x[0]/1e9,1),K[x[2]][0]) for x in rank if x[2] in K))
