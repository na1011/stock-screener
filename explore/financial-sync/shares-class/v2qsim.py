# v2' : 값 = 그 분기 3개월 EPS 분모 n_q(10-K는 FY×일수 − Q1..Q3 일수 가중으로 Q4를 낸다 · 10-Q n은 인스턴스 또는 companyfacts 축 없는 값).
# 라벨 실패 = 폐합 실패/분모 없음/Q4 유도 불가, 또는 |n_q/n_{q-1} − 1| > τ(전이). 발행사 제외 = 보존 창 안 실패 라벨.
import json,os,re,sys,collections,datetime as dt
D=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,D)
from rule2 import load,rule
todo=json.load(open(os.path.join(D,sys.argv[1]))); W=sys.argv[2]; TAU=float(sys.argv[3])
def cfq(t):
    p=os.path.join(D,'tcf',t+'.json'); q={}; forms={}
    if not os.path.exists(p): return q,forms
    fa=json.load(open(p))['facts']
    for node in fa.get('us-gaap',{}).values():
        for arr in node.get('units',{}).values():
            for x in arr: forms.setdefault(x['accn'],(x.get('form',''),x['filed']))
    for x in fa.get('us-gaap',{}).get('WeightedAverageNumberOfSharesOutstandingBasic',{}).get('units',{}).get('shares',[]):
        if 'start' in x:
            d=(dt.date.fromisoformat(x['end'])-dt.date.fromisoformat(x['start'])).days
            if 80<d<100: q[(x['start'],x['end'])]=x['val']
    return q,forms
by=collections.defaultdict(list)
for g,t,c,a,f in todo: by[(t,c)].append((f,a))
days=lambda p:(dt.date.fromisoformat(p[1])-dt.date.fromisoformat(p[0])).days+1
res={}
for (t,c),fl in sorted(by.items()):
    q,forms=cfq(t); st=[]; prev=None
    seq=sorted(set(fl))
    for f,a in seq:
        form=forms.get(a,('10-Q',))[0]
        try: F,cover=load(os.path.join(D,'tinst',f'{c}_{a}.xml'))
        except Exception: st.append((f,'X','noinst')); continue
        n,k,P=rule(t,F,cover,form)
        if n is None or 'FAIL' in k: st.append((f,'X',k.split('/')[0])); continue
        if form.startswith('10-Q'): nq=n; q[P]=n
        else:
            ys=dt.date.fromisoformat(P[0]); ye=dt.date.fromisoformat(P[1])
            qs=[(p,v) for p,v in q.items() if dt.date.fromisoformat(p[0])>=ys-dt.timedelta(days=7) and dt.date.fromisoformat(p[1])<ye-dt.timedelta(days=20)]
            if len(qs)!=3: st.append((f,'X','no-q4')); continue
            tot=days(P); q4d=tot-sum(days(p) for p,_ in qs)
            nq=(n*tot-sum(v*days(p) for p,v in qs))/q4d
        if prev and abs(nq/prev-1)>TAU: st.append((f,'X',f'jump {prev/1e6:.0f}->{nq/1e6:.0f}M'))
        else: st.append((f,'O',k.split('/')[0]))
        prev=nq
    res[t]=st
ex={t:[f"{d}:{k}" for d,s,k in st if s=='X' and d>=W] for t,st in res.items()}
ex={t:v for t,v in ex.items() if v}
print('τ',TAU,'발행사',len(res),'보존 창 안 실패로 빠짐',len(ex))
for t,v in sorted(ex.items()): print('  ',t,v[:3])
