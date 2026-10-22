import sys,os,json,re,time,html,threading,urllib.request,urllib.error
from concurrent.futures import ThreadPoolExecutor
S,O=sys.argv[1],sys.argv[2]; UA={'User-Agent':os.environ['SEC_EDGAR_USER_AGENT']}
px=json.load(open(f'{S}/dep/cand_px.json'))['bars']
dep=re.compile(r'american depositary|\bADS\b|\bADR\b|depositary receipt|depository',re.I)
rows=[]
for l in open(f'{S}/dep/cand.tsv'):
    c=l.rstrip('\n').split('\t')
    t,cik,sh,rc,name=c[0],c[1],c[2],c[5],c[-1]
    if t not in px or not sh or not cik: continue
    rows.append((float(sh)*px[t][0],t,cik,rc,name))
rows.sort(reverse=True); top=rows[:1300]
tg=[r for r in top if not re.match(r'(20-F|40-F|6-K)',r[3]) and not dep.search(r[4])]
seen=set(); tg=[r for r in tg if not (r[2] in seen or seen.add(r[2]))]
print('top1300',len(top),'targets',len(tg),flush=True)
lock=threading.Lock(); last=[0.0]; calls=[0]
def get(u):
    with lock:
        w=last[0]+0.13-time.time()
        if w>0: time.sleep(w)
        last[0]=time.time(); calls[0]+=1
    for k in range(3):
        try: return urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=60).read()
        except urllib.error.HTTPError as e:
            if e.code in (429,503): time.sleep(5); continue
            raise
    raise RuntimeError(u)
out={}
def one(r):
    cap,t,cik,rc,name=r; rec={'t':t,'cap':cap,'name':name}
    try:
        sub=json.loads(get(f'https://data.sec.gov/submissions/CIK{int(cik):010d}.json'))['filings']['recent']
        idx=[i for i,f in enumerate(sub['form']) if f in('10-K','10-Q') and sub['isXBRL'][i]]
        if not idx: rec['err']='no xbrl'; 
        else:
            i=idx[0]; acc=sub['accessionNumber'][i]; base=f'https://www.sec.gov/Archives/edgar/data/{int(cik)}/{acc.replace("-","")}'
            ix=json.loads(get(base+'/index.json')); inst=[x['name'] for x in ix['directory']['item'] if x['name'].endswith('_htm.xml')]
            if len(inst)!=1: rec['err']='inst %d'%len(inst)
            else:
                x=get(base+'/'+inst[0]).decode('utf-8','replace')
                rec['acc']=acc; rec['title']=[html.unescape(re.sub(r'<[^>]+>','',m.group(1))).strip() for m in re.finditer(r'<dei:Security12bTitle\b[^>]*>(.*?)</dei:Security12bTitle>',x,re.S)]
    except Exception as e: rec['err']=repr(e)[:100]
    with lock: out[cik]=rec
with ThreadPoolExecutor(4) as ex: list(ex.map(one,tg))
json.dump(out,open(f'{O}/k10.json','w')); print('calls',calls[0],'done',len(out),'err',sum('err' in v for v in out.values()))
