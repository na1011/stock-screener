import json,sys,collections,csv
S=sys.argv[1]
h=json.load(open(f'{S}/dep/hist.json')); ev=json.load(open(f'{S}/b2/events.json'))
par=collections.defaultdict(dict)   # cik -> acc -> (sym,k)
symc=collections.defaultdict(collections.Counter)
for l in open(f'{S}/b2/ratio.tsv'):
    cik,filed,form,acc,sym,k,title=l.rstrip('\n').split('\t')
    if sym: symc[cik][sym]+=1
    par[cik].setdefault(acc,[]).append((sym,float(k) if k else None))
res=collections.Counter(); unres=collections.defaultdict(list); flips=[]
for cik in symc:
    sym=symc[cik].most_common(1)[0][0]
    fil=sorted([r for r in h.values() if r.get('cik')==cik and 'acc' in r and r['filed']>='2019-01-01'],key=lambda r:r['filed'])
    evs=sorted(e[1] for e in ev.get(sym,[]))
    ks=[]
    def ordsh(r):
        v=[float(x) for c,x in r.get('shares',[]) if x]
        return sum(v) if v and sum(v)>0 else None
    def near(a,b):
        x,y=ordsh(a),ordsh(b)
        return x is not None and y is not None and max(x,y)/min(x,y)<=1.5
    for r in fil:
        k=None; how=None
        for s,kk in par[cik].get(r['acc'],[]):
            if s==sym and kk: k,how=kk,'own'
        if k is None:
            tags=[float(v) for c,v in r.get('ratio',[]) if v]
            if len(set(tags))==1: k,how=tags[0],'tag'
        ks.append([r,k,how])
    for i,(r,k,how) in enumerate(ks):
        if k is None:
            # prev
            for j in range(i-1,-1,-1):
                if ks[j][1] is not None and ks[j][2] in ('own','tag'):
                    if not any(ks[j][0]['filed']<e<=r['filed'] for e in evs) and near(ks[j][0],r): k,how=ks[j][1],'carry-fwd'
                    break
        if k is None:
            for j in range(i+1,len(ks)):
                if ks[j][1] is not None and ks[j][2] in ('own','tag'):
                    if not any(r['filed']<e<=ks[j][0]['filed'] for e in evs) and near(ks[j][0],r): k,how=ks[j][1],'carry-back'
                    break
        ks[i][1],ks[i][2]=k,how or 'UNRESOLVED'
        res[ks[i][2]]+=1
        if k is None and r['filed']>='2020-09-01': unres[sym].append((r['filed'],r['form']))
    # unit flip guard: ADS units vs prev
    prev=None
    for r,k,how in ks:
        sh=[float(v) for c,v in r.get('shares',[]) if v]
        if not sh or k is None or sum(sh)<=0: prev=None; continue
        ads=sum(sh)/k
        if prev:
            f=1.0
            for e in ev.get(sym,[]):
                if prev[0]<e[1]<=r['filed']: f*=e[3]/e[2]
            ratio=ads/(prev[1]*f)
            if k>=2 and abs(ratio*k-1)<0.05:
                flips.append((sym,r['filed'],r['form'],round(ratio,4),k)); continue   # this row already ADS units; keep prev ordinary row
        prev=(r['filed'],ads)
print(dict(res)); print('UNRESOLVED >=2020-09:',{s:v for s,v in unres.items()})
print('unit flips (ADS-unit step ~ k or 1/k):',[f for f in flips if f[4]!=1])
