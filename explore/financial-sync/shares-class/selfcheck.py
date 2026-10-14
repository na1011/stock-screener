import json,os,datetime as dt,collections
D='/Users/na1011/dev/stock-screener/.factcache'
fc={t:r for t,*r in json.load(open('fc.json'))}
c=collections.Counter(); off=[]
for t,(cik,n,m,lat,ld) in fc.items():
    if m==0: continue
    fa=json.load(open(os.path.join(D,t+'.json')))['facts']; g=fa.get('us-gaap',{})
    dei={x['accn'] for x in fa.get('dei',{}).get('EntityCommonStockSharesOutstanding',{}).get('units',{}).get('shares',[])}
    def own(tag,unit):
        s={}
        for u,arr in g.get(tag,{}).get('units',{}).items():
            if unit and u!=unit: continue
            for x in arr:
                if 'start' not in x or x.get('filed','')<'2020-01-01': continue
                d=(dt.date.fromisoformat(x['end'])-dt.date.fromisoformat(x['start'])).days
                if (x.get('form','').startswith('10-K') and 350<d<380) or (x.get('form','').startswith('10-Q') and 80<d<100): 
                    if x['accn'] not in s or x['end']>s[x['accn']][0]: s[x['accn']]=(x['end'],x['start'],x['val'])
        return s
    ownr=own
    def own(tag,unit): return {a:v for a,v in ownr(tag,unit).items()}
    w=own('WeightedAverageNumberOfSharesOutstandingBasic','shares'); e=own('EarningsPerShareBasic',None)
    na=own('NetIncomeLossAvailableToCommonStockholdersBasic',None); ni=own('NetIncomeLoss',None)
    for a,wv in w.items():
        if a in dei: continue
        wend,wst,wv=wv
        if wv==0: c['w=0']+=1; continue
        ev=e.get(a)
        if not ev or ev[:2]!=(wend,wst) or ev[2]==0: c['no eps/zero']+=1; continue
        ev=ev[2]
        nn=na.get(a) if na.get(a) and na[a][:2]==(wend,wst) else (ni.get(a) if ni.get(a) and ni[a][:2]==(wend,wst) else None)
        num=nn[2] if nn else None
        if num is None: c['no numerator']+=1; continue
        imp=num/ev; tol=0.005/abs(ev)+0.005
        r=wv/imp if imp else float('inf')
        k='ok' if abs(r-1)<=tol else ('scale' if 900<r<1100 or 1/1100<r<1/900 else 'off')
        c[k]+=1
        if k!='ok': off.append((t,a,round(r,3),'avail' if nn is na.get(a) else 'NI'))
print(c)
cc=collections.Counter(x[0] for x in off); print(len(cc), cc.most_common(30))
