# v2' 다시 잼(10-14 밤): v2qsim.py의 Q4 유도 결함 둘을 고친다.
#  ① Q4 입력은 그 회계연도 10-Q가 자기 공시 시점에 낸 값만 쓴다(인스턴스 규칙 값 · 없으면 companyfacts에서 그 accn의 축 없는 3개월 분모).
#     최신 판(분할 뒤 재게재)으로 빈칸을 채우지 않는다. 셋이 다 없으면 Q4 유도 불가 = 실패.
#  ② 분할은 corporate_action(APPLIED·APPROVED)의 비율로 같은 기저에 맞춘다 — 공시의 기저는 공시일까지 발효한 분할 전부(ASC 260 소급).
#     전이 검사 |n_q / (n_{q-1}·s) − 1| > τ 의 s도 같다.
# 대상 공시: pop_todo(축 없는 표지 dei가 없는 앵커). 보존 창 판정: 창 시작에 보이던 판(창 시작 이전 마지막 앵커)부터.
import json,os,sys,collections,datetime as dt
D=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,D)
from rule3 import load,rule,closes
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
for g,t,c,a,f in todo: by[(t,c)].append((f,a))
anch={}; res={}; why=collections.Counter(); q4src=collections.Counter()
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
    for f,a in sorted(set(fl)):
        form=forms.get(a,('10-Q',f))[0]
        try: F,cover=load(os.path.join(D,'tinst',f'{c}_{a}.xml'))
        except Exception: st.append((f,'X','noinst')); prev=None; continue
        n,k,P=rule(t,F,cover,form)
        if n is None or 'FAIL' in k: st.append((f,'X',k.split('/')[0])); prev=None; continue
        if form.startswith('10-Q'):
            nq=n; tenq[a]=(f,P,n,'inst')
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
        if t in VERB: print(t,f,form[:5],k.split('/')[0],round(nq/1e6,1),'prev',None if not prev else round(prev[1]*sfac(t,prev[0],f)/1e6,1))
        if prev:
            pv=prev[1]*sfac(t,prev[0],f)
            if abs(nq/pv-1)>TAU: st.append((f,'X',f'jump {pv/1e6:.1f}->{nq/1e6:.1f}M')); prev=(f,nq); continue
        st.append((f,'O',k.split('/')[0])); prev=(f,nq)
    res[t]=st; anch[t]=sorted(fd for a,(fm,fd) in forms.items() if fm in('10-K','10-Q','20-F','40-F','10-K/A','10-Q/A'))
ex={}
for t,st in res.items():
    before=[d for d in anch[t] if d<=W]; w0=max(before) if before else W  # 창 시작에 보이던 판 = 그날 이전 마지막 앵커(축 없는 dei 공시 포함)
    bad=[f"{d}:{k}" for d,s,k in st if s=='X' and d>=w0]
    if bad: ex[t]=bad
for t,st in res.items():
    for d,s,k in st:
        if s=='X': why[k.split(' ')[0].split('(')[0]]+=1
print('τ',TAU,'발행사',len(res),'보존 창 안 실패로 빠짐',len(ex),'| 실패 갈래(창 전체)',dict(why),'| Q4 입력 출처',dict(q4src))
for t,v in sorted(ex.items()): print('  ',t,v[:3])
