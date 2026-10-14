# 표적 드라이런 재료: 가설마다 고른 발행사의 2020-09 뒤 앵커 공시 중 축 없는 표지 dei가 없는 것의 인스턴스만.
# 고른 근거는 SHARES-CLASS-PLAN §3(표적 드라이런). 판정 뒤 이 폴더째 지운다.
import json,os,re,sys,time,datetime,urllib.request,urllib.error,gzip
D=os.path.dirname(os.path.abspath(__file__)); UA=os.environ['SEC_EDGAR_USER_AGENT']
T={'H1-안정구조':['TPG','OWL','SYM','ACN','TW','ASTS','RPRX'],'H1-경제적발행':['MSTR','RIVN','WSO'],
   'H1-전이':['RKT','CVNA','FIG','CRCL','CHYM','UWMC'],'H2-축척':['TBLA','RPAY','TEM','NVMI','DDS','ECHO'],
   'H3-신규':['V','BRK.B','STZ','GEF'],'H4-뒤집힘':['HEI'],'대조군':['GOOGL','META']}
def get(u):
    n=datetime.datetime.now().time()
    if datetime.time(13,20)<=n<datetime.time(15,45): sys.exit('13:20-15:45 창')
    for a in range(4):
        try:
            r=urllib.request.urlopen(urllib.request.Request(u,headers={'User-Agent':UA,'Accept-Encoding':'gzip'}),timeout=90)
            b=r.read(); b=gzip.decompress(b) if r.headers.get('Content-Encoding')=='gzip' else b
            time.sleep(0.13); return b
        except urllib.error.HTTPError as e:
            if e.code==404: raise
            time.sleep(2*(a+1))
    raise RuntimeError(u)
tick=json.load(open(os.path.join(D,'tick.json'))); cik={r[2].replace('-','.'):r[0] for r in tick['data']}
os.makedirs(os.path.join(D,'tcf'),exist_ok=True); os.makedirs(os.path.join(D,'tinst'),exist_ok=True)
calls=0; todo=[]
for g,ts in T.items():
    for t in ts:
        c=cik[t]; p=os.path.join(D,'tcf',t+'.json')
        if not os.path.exists(p): open(p,'wb').write(get(f'https://data.sec.gov/api/xbrl/companyfacts/CIK{int(c):010d}.json')); calls+=1
        fa=json.load(open(p))['facts']
        dei={x['accn'] for x in fa.get('dei',{}).get('EntityCommonStockSharesOutstanding',{}).get('units',{}).get('shares',[])}
        accs={}
        for node in fa.get('us-gaap',{}).values():
            for arr in node.get('units',{}).values():
                for x in arr:
                    if x.get('form') in('10-K','10-Q','20-F','40-F') and x.get('filed','')>='2020-09-01' and x['accn'] not in dei: accs[x['accn']]=x['filed']
        todo+= [(g,t,c,a,f) for a,f in accs.items()]
print('companyfacts calls',calls,'instances',len(todo),flush=True)
json.dump(todo,open(os.path.join(D,'target_todo.json'),'w'))
fail=0
for g,t,c,a,f in todo:
    p=os.path.join(D,'tinst',f'{c}_{a}.xml')
    if os.path.exists(p): continue
    try:
        idx=json.loads(get(f'https://www.sec.gov/Archives/edgar/data/{int(c)}/{a.replace("-","")}/index.json')); calls+=1
        names=[i['name'] for i in idx['directory']['item']]
        cand=[n for n in names if n.endswith('.xml') and not re.search(r'(_cal|_def|_lab|_pre)\.xml$|FilingSummary|MetaLinks',n)]
        pick=[n for n in cand if n.endswith('_htm.xml')] or cand
        open(p,'wb').write(get(f'https://www.sec.gov/Archives/edgar/data/{int(c)}/{a.replace("-","")}/{pick[0]}')); calls+=1
    except Exception as e: fail+=1; print('fail',t,a,e,flush=True)
print('total calls',calls,'fail',fail)
