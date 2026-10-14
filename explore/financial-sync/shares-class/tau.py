import json,os,collections,datetime as dt
D='/Users/na1011/dev/stock-screener/.factcache'
fc={t:(c,n,m) for t,c,n,m,*_ in json.load(open('fc.json'))}
devs=[]; per_t={}
for f in sorted(os.listdir(D)):
    t=f[:-5]
    if t not in fc or fc[t][2]>0: continue   # single-class: no filing missing undim dei
    j=json.load(open(os.path.join(D,f))); fa=j.get('facts',{})
    dei={x['accn']:x['val'] for x in fa.get('dei',{}).get('EntityCommonStockSharesOutstanding',{}).get('units',{}).get('shares',[])}
    w={}
    for x in fa.get('us-gaap',{}).get('WeightedAverageNumberOfSharesOutstandingBasic',{}).get('units',{}).get('shares',[]):
        if x.get('form') not in('10-Q','10-K') or x['filed']<'2023-01-01' or 'start' not in x: continue
        d=(dt.date.fromisoformat(x['end'])-dt.date.fromisoformat(x['start'])).days
        if x.get('fp')=='FY' and x['form']=='10-K' and 350<d<380 or x['form']=='10-Q' and 80<d<100:
            # only the filing's own current period
            w.setdefault(x['accn'],[]).append((x['end'],x['val']))
    for a,lst in w.items():
        if a not in dei or not dei[a]: continue
        end,val=max(lst)
        r=val/dei[a]-1; devs.append(r); per_t.setdefault(t,[]).append(r)
devs.sort(key=abs)
n=len(devs)
q=lambda p: abs(devs[min(n-1,int(p*n))])
print('single-class filings',n,'tickers',len(per_t))
for p in (.5,.9,.95,.99,.995): print(p, round(q(p),4))
print('share >3%:',round(sum(1 for d in devs if abs(d)>.03)/n,4),' >5%:',round(sum(1 for d in devs if abs(d)>.05)/n,4),' >10%:',round(sum(1 for d in devs if abs(d)>.10)/n,4))
