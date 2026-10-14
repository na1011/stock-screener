# 발행사 단위 판정: 2020-09 뒤 축 없는 dei가 없는 앵커 공시 전부가 확인(폐합·분모·연속 r 변화 ≤ τ)되면 남고, 아니면 뺀다.
import json,os,re,sys,collections
D=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,D)
from rule2 import load,rule
TAU=0.074
todo=json.load(open(os.path.join(D,sys.argv[1] if len(sys.argv)>1 else 'pop_todo.json')))
forms={}
for t in set(x[1] for x in todo):
    p=os.path.join(D,'tcf',t+'.json')
    if not os.path.exists(p): continue
    for node in json.load(open(p))['facts'].get('us-gaap',{}).values():
        for arr in node.get('units',{}).values():
            for x in arr: forms.setdefault(x['accn'],x.get('form',''))
by=collections.defaultdict(list)
for g,t,c,a,f in todo: by[(t,c)].append((f,a))
res=[]
for (t,c),fl in sorted(by.items()):
    prev=None; why=None; n_ok=0; last=None
    for f,a in sorted(fl):
        p=os.path.join(D,'tinst',f'{c}_{a}.xml')
        if not os.path.exists(p): why=why or f'인스턴스 없음 {a}'; continue
        F,cover=load(p); n,k,P=rule(t,F,cover,forms.get(a,'10-Q'))
        if n is None: why=why or f'{k.split("/")[0]} {f}'; continue
        if 'FAIL' in k: why=why or f'{k} {f}'; continue
        sh=cover.get('EntityCommonStockSharesOutstanding',{})
        cls=[float(v) for m,v in sh.items() if m!='-' and 'And' not in m.split(':')[-1] and float(v)>0 and not re.search(r'Preferred|Depositar|Note|Warrant',m,re.I)]
        sd=sum(cls) if cls else (float(sh['-']) if '-' in sh else None)
        r=sd/n if sd else None
        if r and prev and abs(r/prev-1)>TAU: why=why or f'r {prev:.2f}→{r:.2f} {f}'
        if r: prev=r
        n_ok+=1; last=n
    res.append((t,why,len(fl),n_ok,last))
keep=[x for x in res if not x[1]]; drop=[x for x in res if x[1]]
print('발행사(줄)',len(res),'남음',len(keep),'빠짐',len(drop))
for x in drop: print('  빠짐',x[0],'|',x[1],'| 공시',x[2])
json.dump(res,open(os.path.join(D,'popsim.json'),'w'),ensure_ascii=False)
