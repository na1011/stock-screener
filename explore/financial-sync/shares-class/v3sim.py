# v3 가설: 값 = 표지 시점 클래스 합(단일 클래스와 같은 개념). 확인 = 그 합 ÷ 최근 분기 3개월 EPS 분모(10-K는 FY×4 − Q1−Q2−Q3)가 |r−1| ≤ τ, 그리고 분모가 폐합.
# 클래스별 EPS가 다르면 환산 합 Σ dei_k·e_k/e_L(dei 구성원 = EPS 구성원일 때만).
import json,os,re,sys,collections,datetime as dt
D=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,D)
import rule2
from rule2 import load,rule,lpmember
TAU=float(sys.argv[3]) if len(sys.argv)>3 else 0.075
todo=json.load(open(os.path.join(D,sys.argv[1]))); W=sys.argv[2]
forms={}
for t in set(x[1] for x in todo):
    p=os.path.join(D,'tcf',t+'.json')
    if os.path.exists(p):
        for node in json.load(open(p))['facts'].get('us-gaap',{}).values():
            for arr in node.get('units',{}).values():
                for x in arr: forms.setdefault(x['accn'],x.get('form',''))
by=collections.defaultdict(list)
for g,t,c,a,f in todo: by[(t,c)].append((f,a))
def cls_of(cover):
    sh=cover.get('EntityCommonStockSharesOutstanding',{})
    return {m:float(v) for m,v in sh.items() if m!='-' and 'And' not in m.split(':')[-1] and float(v)>0 and not re.search(r'Preferred|Depositar|Note|Warrant|Right|Unit',m,re.I)}, sh.get('-')
res={}
for (t,c),fl in sorted(by.items()):
    qhist={}  # period(start,end) -> 3m n
    st=[]
    for f,a in sorted(fl):
        form=forms.get(a,'10-Q')
        try: F,cover=load(os.path.join(D,'tinst',f'{c}_{a}.xml'))
        except Exception: st.append((f,'X','noinst')); continue
        n,k,P=rule(t,F,cover,form)
        if n is None or 'FAIL' in k: st.append((f,'X',k.split('/')[0])); continue
        if form.startswith('10-Q'): qhist[P]=n; nq=n
        else:
            # 10-K: Q4 3개월 = FY×일수 − ΣQ×일수 (일수 가중) — 같은 FY 안 10-Q 셋이 있을 때만
            s,e=P; ys=dt.date.fromisoformat(s); ye=dt.date.fromisoformat(e)
            qs=[(p,v) for p,v in qhist.items() if dt.date.fromisoformat(p[0])>=ys-dt.timedelta(days=7) and dt.date.fromisoformat(p[1])<ye]
            if len(qs)!=3: st.append((f,'X','no-q4')); continue
            days=lambda p:(dt.date.fromisoformat(p[1])-dt.date.fromisoformat(p[0])).days+1
            tot=(ye-ys).days+1; q4d=tot-sum(days(p) for p,_ in qs)
            nq=(n*tot-sum(v*days(p) for p,v in qs))/q4d
        cl,und=cls_of(cover)
        if k.startswith('B-sum') or k.startswith('B-member'):
            # 클래스별 EPS가 다른 발행사: 표지 환산 합 — dei 구성원과 EPS 구성원이 같아야 한다
            e={m:v for (m,p),v in F['EarningsPerShareBasic'].items() if p==P and m!='-'}
            Lm,_=lpmember(t,cover,list(e))
            if not Lm or not all(m in e for m in cl): st.append((f,'X','member-mismatch')); continue
            pit=sum(v*e[m]/e[Lm] for m,v in cl.items())
        else:
            pit=sum(cl.values()) if cl else (float(und) if und else None)
        if not pit: st.append((f,'X','no-dei')); continue
        r=pit/nq
        st.append((f,'O' if abs(r-1)<=TAU else 'X', f'r={r:.3f}'))
    res[t]=st
ex={t:[f"{d}:{k}" for d,s,k in st if s=='X' and d>=W] for t,st in res.items()}
ex={t:v for t,v in ex.items() if v}
print('발행사',len(res),'보존 창 안 확인 실패로 빠짐',len(ex))
for t,v in sorted(ex.items()): print('  ',t,v[:4])
json.dump(res,open(os.path.join(D,'v3sim.json'),'w'))
