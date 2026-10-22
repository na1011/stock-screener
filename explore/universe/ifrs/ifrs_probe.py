import sys,os,json,time,urllib.request,urllib.error,threading
from concurrent.futures import ThreadPoolExecutor
D=sys.argv[1]; UA={'User-Agent':os.environ['SEC_EDGAR_USER_AGENT']}
lock=threading.Lock(); last=[0.0]; calls=[0]
def get(u):
    with lock:
        w=last[0]+0.13-time.time()
        if w>0: time.sleep(w)
        last[0]=time.time(); calls[0]+=1
    for k in range(3):
        try: return json.loads(urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=60).read())
        except urllib.error.HTTPError as e:
            if e.code==404: return None
            if e.code in (429,503): time.sleep(5); continue
            raise
    raise RuntimeError(u)
ciks=[l.split('\t')[0] for l in open(f'{D}/nofs_cand.tsv')]
out={}
def one(cik):
    base=f'https://data.sec.gov/api/xbrl/companyconcept/CIK{int(cik):010d}'
    r={'cik':cik}
    try:
        sh=get(base+'/dei/EntityCommonStockSharesOutstanding.json')
        if sh:
            u=sh['units'].get('shares',[])
            if u:
                lastf=max(x['filed'] for x in u); r['shares']=sum(x['val'] for x in u if x['filed']==lastf and x.get('end')==max(y['end'] for y in u if y['filed']==lastf)); r['sh_filed']=lastf
        for tax,tag in (('ifrs-full','ProfitLoss'),('ifrs-full','Revenue'),('us-gaap','NetIncomeLoss')):
            c=get(f'{base}/{tax}/{tag}.json')
            if c:
                r.setdefault('tax',[]).append(tax+':'+tag+':'+','.join(sorted(c['units'])))
                if tax=='ifrs-full': break
    except Exception as e: r['err']=repr(e)[:100]
    with lock: out[cik]=r
with ThreadPoolExecutor(4) as ex: list(ex.map(one,ciks))
json.dump(out,open(f'{D}/probe.json','w')); print('calls',calls[0],'ciks',len(out))
