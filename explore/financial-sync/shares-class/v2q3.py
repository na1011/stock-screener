# v2q3 = 혼합안: 원장이 참여 구성원을 말하면(구성원 EPS·구성원 분모) 시점 값 N=Σ표지_k·e_k/e_L, 아니면 v2'.
# (v2q2.py 머리말) v2' 다시 잼(10-14 밤): v2qsim.py의 Q4 유도 결함 둘을 고친다.
#  ① Q4 입력은 그 회계연도 10-Q가 자기 공시 시점에 낸 값만 쓴다(인스턴스 규칙 값 · 없으면 companyfacts에서 그 accn의 축 없는 3개월 분모).
#     최신 판(분할 뒤 재게재)으로 빈칸을 채우지 않는다. 셋이 다 없으면 Q4 유도 불가 = 실패.
#  ② 분할은 corporate_action(APPLIED·APPROVED)의 비율로 같은 기저에 맞춘다 — 공시의 기저는 공시일까지 발효한 분할 전부(ASC 260 소급).
#     전이 검사 |n_q / (n_{q-1}·s) − 1| > τ 의 s도 같다.
# 대상 공시: pop_todo(축 없는 표지 dei가 없는 앵커). 보존 창 판정: 창 시작에 보이던 판(창 시작 이전 마지막 앵커)부터.
import json,os,sys,collections,datetime as dt
D=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,D)
import importlib; _R=importlib.import_module(os.environ.get('RULE','rule3')); load,rule,ETAGS,WTAGS=_R.load,_R.rule,_R.ETAGS,_R.WTAGS
from rule3 import closes
todo=json.load(open(os.path.join(D,sys.argv[1]))); W=sys.argv[2]; TAU=float(sys.argv[3]); VERB=set(sys.argv[4].split(',')) if len(sys.argv)>4 else set()
SPL=collections.defaultdict(list)
for l in open(os.path.join(D,'splits.tsv')):
    t,d,r,_=l.rstrip('\n').split('\t'); SPL[t].append((d,float(r)))
def sfac(t,a,b):  # a < 발효일 ≤ b 인 분할 비율의 곱
    f=1.0
    for d,r in SPL.get(t,()):
        if a<d<=b: f*=r
    return f
days=lambda p:(dt.date.fromisoformat(p[1])-dt.date.fromisoformat(p[0])).days+1
def cfdata(t):
    fa=json.load(open(os.path.join(D,'tcf',t+'.json')))['facts']; forms={}
    for node in fa.get('us-gaap',{}).values():
        for arr in node.get('units',{}).values():
            for x in arr: forms.setdefault(x['accn'],(x.get('form',''),x['filed']))
    q3={}  # accn -> {(start,end): val} 축 없는 3개월 분모, 그 공시가 실은 값
    ef={}  # (accn,period) -> (eps, 분자) — 인스턴스 갈래와 같은 폐합 검사를 Q4 입력에도 건다
    def pick(tags,unit):
        out={}
        for tag in tags:
            for x in fa.get('us-gaap',{}).get(tag,{}).get('units',{}).get(unit,[]):
                if 'start' in x: out.setdefault((x['accn'],(x['start'],x['end'])),x['val'])
        return out
    EPS=pick(('EarningsPerShareBasic','EarningsPerShareBasicAndDiluted','IncomeLossFromContinuingOperationsPerBasicShare'),'USD/shares')
    NUM={}
    for tag in ('NetIncomeLossAvailableToCommonStockholdersBasic','NetIncomeLoss','ProfitLoss'):
        for k,v in pick((tag,),'USD').items(): NUM.setdefault(k,v)
    for tag in ('WeightedAverageNumberOfSharesOutstandingBasic','WeightedAverageNumberOfShareOutstandingBasicAndDiluted'):
        for x in fa.get('us-gaap',{}).get(tag,{}).get('units',{}).get('shares',[]):
            if 'start' in x and 80<days((x['start'],x['end']))-1<100: q3.setdefault(x['accn'],{}).setdefault((x['start'],x['end']),x['val'])
    return forms,q3,EPS,NUM
by=collections.defaultdict(list)
MC={}
for g,t,c,a,f in todo:
    if f>=W: MC[t]=True
for g,t,c,a,f in todo: by[(t,c)].append((f,a))
TAUC_PT=float(os.environ.get('TAUC_PT','0.044'))
RAT=[]; anch={}; res={}; PT2=collections.Counter(); why=collections.Counter(); q4src=collections.Counter()
for (t,c),fl in sorted(by.items()):
    forms,q3,EPS,NUM=cfdata(t)
    # 그 발행사의 모든 10-Q(앵커 여부 무관): 자기 3개월 기간과 그 공시 시점 값
    tenq={}
    for a,(fm,fd) in forms.items():
        if fm.startswith('10-Q') and a in q3:
            P=max(q3[a],key=lambda p:p[1]); v=q3[a][P]
            ok=closes(v,EPS.get((a,P)),NUM.get((a,P))) if v else False
            tenq[a]=(fd,P,v,'cf' if ok is not False else 'cf-FAIL')
    st=[]; prev=None
    fa_=json.load(open(os.path.join(D,'tcf',t+'.json')))['facts']
    dei={x['accn']:x['val'] for x in fa_.get('dei',{}).get('EntityCommonStockSharesOutstanding',{}).get('units',{}).get('shares',[])}
    mine={a for f,a in fl}
    dseq=[(fd,a) for a,(fm,fd) in forms.items() if fm in('10-K','10-Q','20-F','40-F') and a not in mine and a in dei and fd>=min(f for f,a in fl)]
    events=sorted([(f,a,'T') for f,a in set(fl)]+[(fd,a,'d') for fd,a in dseq])
    for f,a,kind in events:
        if kind=='d':
            v=dei[a]
            if MC.get(t) and forms.get(a,('',))[0]=='10-Q' and a in q3:
                wq=q3[a][max(q3[a],key=lambda p:p[1])]
                if wq and abs(v/wq-1)>TAUC_PT:
                    PT2['d->avg']+=1; v=wq   # 참여를 원장이 확인 못 함 → 평균
                    if prev and abs(v/(prev[1]*sfac(t,prev[0],f))-1)>TAU: st.append((f,'X',f'jump-d {prev[1]/1e6:.1f}->{v/1e6:.1f}M')); prev=(f,v); continue
            if prev and v>0 and t in VERB: print(t,f,'d-dei',round(v/1e6,1),'prev',round(prev[1]*sfac(t,prev[0],f)/1e6,1))
            if v>0: st.append((f,'O','d')); prev=(f,v)
            continue
        form=forms.get(a,('10-Q',f))[0]
        try: F,cover=load(os.path.join(D,'tinst',f'{c}_{a}.xml'))
        except Exception: st.append((f,'X','noinst')); prev=None; continue
        n,k,P=rule(t,F,cover,form)
        if n is None or 'FAIL' in k: st.append((f,'X',k.split('/')[0])); prev=None; continue
        # 시점 값: 원장이 참여를 구성원마다 말하고, 그 구성원 전부에 표지 값이 있을 때만
        em={m:v for tg in ETAGS for (m,p),v in F[tg].items() if p==P and m!='-'}
        wm={m for tg in WTAGS for (m,p) in F[tg] if p==P and m!='-'}
        cv={m:float(v) for m,v in cover.get('EntityCommonStockSharesOutstanding',{}).items() if m!='-' and float(v)>0}
        S=set(em) or wm; Npt=None
        if S and S<=set(cv):
            if not em or len({round(v,4) for v in em.values()})==1: Npt=sum(cv[m] for m in S)
            else:
                from rule3 import lpmember
                Lm,_=lpmember(t,cover,list(em))
                if Lm and em[Lm]: Npt=sum(cv[m]*em[m] for m in S)/em[Lm]
        if form.startswith('10-Q'): tenq[a]=(f,P,n,'inst')
        if Npt is None and cv and form.startswith('10-Q') and abs(sum(cv.values())/n-1)<=TAUC_PT:
            Npt=sum(cv.values()); PT2['todo-c']+=1
        if Npt is not None:
            RAT.append((t,f,Npt/n))
            if t in VERB: print(t,f,form[:5],'POINT',round(Npt/1e6,1),'n',round(n/1e6,1))
            st.append((f,'O','P')); prev=(f,Npt); continue
        if form.startswith('10-Q'):
            nq=n
        else:
            ys,ye=P
            qs=[(fd,p,v,s) for fd,p,v,s in tenq.values() if p[0]>=str(dt.date.fromisoformat(ys)-dt.timedelta(days=7)) and p[1]<str(dt.date.fromisoformat(ye)-dt.timedelta(days=20)) and fd<f]
            byp={}
            for fd,p,v,s in sorted(qs): byp.setdefault(p,(fd,v,s))  # 같은 분기를 여러 공시가 실으면 첫 판(자기 공시)
            if len(byp)!=3: st.append((f,'X',f'no-q4({len(byp)})')); prev=None; continue
            if any(s=='cf-FAIL' for fd,v,s in byp.values()): st.append((f,'X','q4-input-FAIL')); prev=None; continue
            tot=days(P); q4d=tot-sum(days(p) for p in byp)
            adj=sum(v*sfac(t,fd,f)*days(p) for p,(fd,v,s) in byp.items())
            nq=(n*tot-adj)/q4d
            for p,(fd,v,s) in byp.items(): q4src[s]+=1
            if nq<=0: st.append((f,'X','q4<=0')); prev=None; continue
            if cv and abs(sum(cv.values())/nq-1)<=TAUC_PT:   # 10-K: 유도한 Q4 분모로 전 클래스 참여 확인 → 시점 값
                PT2['todo-c-10K']+=1; st.append((f,'O','P2')); prev=(f,sum(cv.values())); continue
        if t in VERB: print(t,f,form[:5],k.split('/')[0],round(nq/1e6,1),'prev',None if not prev else round(prev[1]*sfac(t,prev[0],f)/1e6,1))
        if prev:
            pv=prev[1]*sfac(t,prev[0],f)
            if abs(nq/pv-1)>TAU: st.append((f,'X',f'jump {pv/1e6:.1f}->{nq/1e6:.1f}M')); prev=(f,nq); continue
        st.append((f,'O',k.split('/')[0])); prev=(f,nq)
    res[t]=st; anch[t]=sorted(fd for a,(fm,fd) in forms.items() if fm in('10-K','10-Q','20-F','40-F','10-K/A','10-Q/A'))
ex={}; pre={}
for t,st in res.items():
    before=[d for d in anch[t] if d<=W]; w0=max(before) if before else W  # 창 시작에 보이던 판 = 그날 이전 마지막 앵커(축 없는 dei 공시 포함)
    win=[(d,s) for d,s,k in st if d>=w0]
    bad=[f"{d}:{k}" for d,s,k in st if s=='X' and d>=w0]
    if not bad: continue
    seq=''.join(s for d,s in win)
    if 'OX' in seq: ex[t]=bad          # 성공 뒤 실패 = 반복 → 발행사 제외
    else: pre[t]=(seq,bad)             # 실패가 창 앞쪽에만 = 한 번 전이 → 그 판만 숨김
for t,st in res.items():
    for d,s,k in st:
        if s=='X': why[k.split(' ')[0].split('(')[0]]+=1
print('τ',TAU,'발행사',len(res),'반복 실패로 발행사 제외',len(ex),'접두 실패(판만 숨김)',len(pre),'| 실패 갈래(창 전체)',dict(why),'| Q4 입력 출처',dict(q4src))
for t,v in sorted(ex.items()): print('  제외',t,v[:3])
for t,v in sorted(pre.items()): print('  접두',t,v[0],v[1][:2])

import math
b=collections.Counter()
for t,f,r in RAT: b[round(math.log10(r),1)]+=1
print('2b',dict(PT2)); print('N/n log10 분포',sorted(b.items()))
print('N/n 밖(0.67~1.5):',[(t,f,round(r,3)) for t,f,r in RAT if not 0.67<r<1.5])
pc=collections.Counter(k for st in res.values() for d,s_,k in st if s_=='O'); print('통과 갈래',pc.most_common(6))
