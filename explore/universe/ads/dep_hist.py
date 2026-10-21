import sys,os,json,re,time,urllib.request,html,threading
from concurrent.futures import ThreadPoolExecutor
S=sys.argv[1]; UA={'User-Agent':os.environ['SEC_EDGAR_USER_AGENT']}
lock=threading.Lock(); last=[0.0]; calls=[0]
def get(u):
    with lock:
        w=last[0]+0.125-time.time()
        if w>0: time.sleep(w)
        last[0]=time.time(); calls[0]+=1
    for k in range(3):
        try: return urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=60).read()
        except urllib.error.HTTPError as e:
            if e.code in (429,503): time.sleep(5); continue
            raise
    raise RuntimeError('retry '+u)
rows=json.load(open(f'{S}/join.json'))
ciks=sorted({r['cik'] for r in rows if r['ownADS'] or r['t']=='MREO'})
path=f'{S}/hist.json'; out=json.load(open(path)) if os.path.exists(path) else {}
FORMS={'20-F','10-K','10-Q','40-F','20-F/A','10-K/A'}
def filings(cik):
    sub=json.loads(get(f'https://data.sec.gov/submissions/CIK{int(cik):010d}.json'))
    cols=[sub['filings']['recent']]
    for f in sub['filings'].get('files',[]):
        if f['filingTo']>='2015-01-01': cols.append(json.loads(get('https://data.sec.gov/submissions/'+f['name'])))
    res=[]
    for c in cols:
        for i,f in enumerate(c['form']):
            if f in FORMS and c['filingDate'][i]>='2015-01-01': res.append((c['accessionNumber'][i],f,c['filingDate'][i],c['isXBRL'][i]))
    return res
def one(cik,acc,form,filed,xb):
    key=cik+'|'+acc
    if key in out: return
    rec={'cik':cik,'acc':acc,'form':form,'filed':filed}
    try:
        base=f'https://www.sec.gov/Archives/edgar/data/{int(cik)}/{acc.replace("-","")}'
        ix=json.loads(get(base+'/index.json'))
        names=[x['name'] for x in ix['directory']['item']]
        inst=[n for n in names if n.endswith('_htm.xml')] or [n for n in names if n.endswith('.xml') and not re.search(r'(_cal|_def|_lab|_pre|_ref)\.xml$|FilingSummary',n)]
        if len(inst)!=1: rec['err']='inst %d'%len(inst)
        else:
            x=get(base+'/'+inst[0]).decode('utf-8','replace')
            def facts(local):
                return [(m.group(1),html.unescape(re.sub(r'<[^>]+>','',m.group(2))).strip()) for m in re.finditer(r'<dei:'+local+r'\b[^>]*contextRef="([^"]+)"[^>]*>(.*?)</dei:'+local+'>',x,re.S)]
            rec.update(title=facts('Security12bTitle'),sym=facts('TradingSymbol'),shares=facts('EntityCommonStockSharesOutstanding'),ratio=facts('EntityListingDepositoryReceiptRatio'))
    except Exception as e: rec['err']=repr(e)[:120]
    with lock: out[key]=rec
jobs=[]
for cik in ciks:
    try: jobs+= [(cik,)+f for f in filings(cik)]
    except Exception as e: out[cik+'|ERR']={'err':repr(e)}
print('filings',len(jobs),flush=True)
with ThreadPoolExecutor(6) as ex:
    for n,_ in enumerate(ex.map(lambda j: one(*j),jobs)):
        if n%500==0:
            with lock: snap=dict(out)
            json.dump(snap,open(path,'w')); print(n,calls[0],flush=True)
json.dump(out,open(path,'w')); print('done calls',calls[0],'recs',len(out))
