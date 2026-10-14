import json,os,re,sys,collections
D=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,D)
from rule2 import load,rule
TAU=0.074
todo=json.load(open(os.path.join(D,sys.argv[1])))
forms={}
for t in set(x[1] for x in todo):
    p=os.path.join(D,'tcf',t+'.json')
    if os.path.exists(p):
        for node in json.load(open(p))['facts'].get('us-gaap',{}).values():
            for arr in node.get('units',{}).values():
                for x in arr: forms.setdefault(x['accn'],x.get('form',''))
latest={}
for t in set(x[1] for x in todo):
    p=os.path.join(D,'tcf',t+'.json')
    if not os.path.exists(p): continue
    fa=json.load(open(p))['facts']
    dei={x['accn'] for x in fa.get('dei',{}).get('EntityCommonStockSharesOutstanding',{}).get('units',{}).get('shares',[])}
    best=None
    for node in fa.get('us-gaap',{}).values():
        for arr in node.get('units',{}).values():
            for x in arr:
                if x.get('form') in('10-K','10-Q','20-F','40-F') and (best is None or x['filed']>best[0]): best=(x['filed'],x['accn'])
    if best: latest[t]=(best[0], best[1] in dei)
by=collections.defaultdict(list)
for g,t,c,a,f in todo: by[(t,c)].append((f,a))
out=[]
for (t,c),fl in sorted(by.items()):
    prev=None; st=[]
    for f,a in sorted(fl):
        p=os.path.join(D,'tinst',f'{c}_{a}.xml')
        try: F,cover=load(p)
        except Exception: st.append((f,'X','noinst')); continue
        n,k,P=rule(t,F,cover,forms.get(a,'10-Q'))
        if n is None or 'FAIL' in k: st.append((f,'X',k.split('/')[0])); continue
        sh=cover.get('EntityCommonStockSharesOutstanding',{})
        cls=[float(v) for m,v in sh.items() if m!='-' and 'And' not in m.split(':')[-1] and float(v)>0 and not re.search(r'Preferred|Depositar|Note|Warrant',m,re.I)]
        sd=sum(cls) if cls else (float(sh['-']) if '-' in sh else None)
        r=sd/n if sd else None
        bad = bool(r and prev and abs(r/prev-1)>TAU)
        if r: prev=r
        st.append((f,'X' if bad else 'O','r-jump' if bad else k.split('/')[0]))
    lf=latest.get(t)
    if lf and lf[1] and (not st or lf[0]>st[-1][0]): st.append((lf[0],'O','dei'))
    recent=[s for s in st if s[0]>='2024-10-01']
    tog=sum(1 for i in range(1,len(recent)) if recent[i][1]!=recent[i-1][1])
    nbad=sum(1 for s in recent if s[1]=='X')
    out.append((t,st[-1][1] if st else '-',st[-1][2] if st else '',len(recent),nbad,tog,[x for x in st if x[1]=='X']))
c=collections.Counter((o[1], 'stable' if o[4]==0 else ('1bad' if o[4]==1 else 'recurrent')) for o in out)
print('발행사',len(out),dict(c))
print('-- 최신 공시 확인 실패(지금 순위 밖):'); print(' ',[f"{o[0]}({o[2]})" for o in out if o[1]=='X'])
print('-- 최근 2년 실패 2회 이상(반복):'); print(' ',[f"{o[0]} {o[4]}/{o[3]} 뒤집힘{o[5]}" for o in out if o[4]>=2])
json.dump(out,open(os.path.join(D,'labelsim.json'),'w'))

W=sys.argv[2] if len(sys.argv)>2 else '2025-10-08'
ex=[(o[0],[f"{d}:{k}" for d,_,k in o[6] if d>=W]) for o in out if any(d>=W for d,_,k in o[6])]
print(f'-- 보존 창({W}~) 안에 확인 실패 라벨이 있어 발행사째 빠지는 것:',len(ex))
for e in ex: print('  ',e[0],e[1])
old=[o[0] for o in out if o[6] and not any(d>=W for d,_,k in o[6])]
print('-- 창 밖 실패만 있어 그 라벨만 숨는 것:',len(old),old)
