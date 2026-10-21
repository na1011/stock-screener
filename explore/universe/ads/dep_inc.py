import sys,os,json,re,time,urllib.request,html
S=sys.argv[1]; UA={'User-Agent':os.environ['SEC_EDGAR_USER_AGENT']}
US={'AL','AK','AZ','AR','CA','CO','CT','DE','FL','GA','HI','ID','IL','IN','IA','KS','KY','LA','ME','MD','MA','MI','MN','MS','MO','MT','NE','NV','NH','NJ','NM','NY','NC','ND','OH','OK','OR','PA','RI','SC','SD','TN','TX','UT','VT','VA','WA','WV','WI','WY','DC','PR'}
path=f'{S}/inc.json'
out=json.load(open(path)) if os.path.exists(path) else {}
import threading
from concurrent.futures import ThreadPoolExecutor
calls=0; lock=threading.Lock(); last=[0.0]
def get(u):
    global calls
    with lock:
        w=last[0]+0.125-time.time()
        if w>0: time.sleep(w)
        last[0]=time.time(); calls+=1
    for k in range(3):
        try: return urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=60).read()
        except urllib.error.HTTPError as e:
            if e.code in (429,503): time.sleep(5); continue
            raise
    raise RuntimeError('retry exhausted '+u)
ciks=sorted({l.split('\t')[1] for l in open(f'{S}/cand.tsv') if l.split('\t')[1]})
def work(cik):
    try:
        sub=json.loads(get(f'https://data.sec.gov/submissions/CIK{int(cik):010d}.json'))
        st=sub.get('stateOfIncorporation') or ''
        r=sub['filings']['recent']
        rec={'state':st,'desc':sub.get('stateOfIncorporationDescription'),'name':sub.get('name')}
        if st not in US:
            idx=[i for i,f in enumerate(r['form']) if f in('20-F','10-K','40-F','20-F/A','10-K/A','40-F/A') and r['isXBRL'][i]]
            if idx:
                i=idx[0]; acc=r['accessionNumber'][i]
                base=f'https://www.sec.gov/Archives/edgar/data/{int(cik)}/{acc.replace("-","")}'
                ix=json.loads(get(base+'/index.json'))
                inst=[x['name'] for x in ix['directory']['item'] if x['name'].endswith('_htm.xml')]
                rec.update(acc=acc,form=r['form'][i],filed=r['filingDate'][i])
                if inst:
                    x=get(base+'/'+inst[0]).decode('utf-8','replace')
                    def facts(local):
                        return [(m.group(1),html.unescape(re.sub(r'<[^>]+>','',m.group(2))).strip()) for m in re.finditer(r'<dei:'+local+r'\b[^>]*contextRef="([^"]+)"[^>]*>(.*?)</dei:'+local+'>',x,re.S)]
                    rec.update(title=facts('Security12bTitle'),sym=facts('TradingSymbol'),shares=facts('EntityCommonStockSharesOutstanding'),ratio=facts('EntityListingDepositoryReceiptRatio'))
                else: rec['err']='no instance'
            else: rec['err']='no annual xbrl'
        out[cik]=rec
    except Exception as e:
        out[cik]={'err':repr(e)}
todo=[c for c in ciks if c not in out]
print('todo',len(todo),flush=True)
with ThreadPoolExecutor(6) as ex:
    for n,_ in enumerate(ex.map(work,todo)):
        if n%500==0:
            with lock: snap=dict(out)
            json.dump(snap,open(path,'w')); print(n,calls,flush=True)
json.dump(out,open(path,'w'))
print('done calls',calls,'ciks',len(out),'err',sum(1 for v in out.values() if 'err' in v and 'state' not in v))
