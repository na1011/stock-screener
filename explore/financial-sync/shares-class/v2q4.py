# v2q4 (10-15 새벽): v3 반증(계획 밖)을 받아 다시 짠 시뮬레이터.
#  값: rule6(rule5 + 단위 복원) · 시점(층 a/b · 층 c는 τ_c 확인) / 평균(Q4는 Q3 10-Q 9개월 누적 우선, 없으면 3개월 셋) · 20-F/40-F는 B 갈래·시점 합 금지.
#  d 공시(축 없는 dei)는 지금 경로 그대로(값 = dei · 확인 없음) — 미국 전 발행사 같은 대접.
#  숨김·제외는 그날까지 공시된 판만 본다(미래 참조 없음):
#   - 판 숨김: 실패 판은 공시일부터 다음 통과 판까지.
#   - 발행사 제외: d일까지 L(255거래일 ≈ 371일) 안에 「원장 어긋남」 구간이 둘 이상 시작했으면 d일에 뺀다.
#     원장 어긋남 = 폐합 실패(단위 복원 뒤) · Q4 입력 폐합 실패 · Q4 ≤ 0. 그 밖(분모 없음 · 기간 없음 · 짝 없음 · Q4 재료 없음 · 전이)은 진짜 한계 → 판 숨김만.
import json,os,sys,collections,datetime as dt
D=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,D)
import rule6
from rule6 import load,ETAGS,WTAGS,NTAGS,lpmember
from rule5 import closes,TAUC
todo=json.load(open(os.path.join(D,sys.argv[1]))); W=sys.argv[2]; TAU=float(sys.argv[3]); TAUC_PT=float(os.environ.get('TAUC_PT','0.044'))
END=os.environ.get('END','2026-10-14'); L=dt.timedelta(days=371)
VERB=set(sys.argv[4].split(',')) if len(sys.argv)>4 else set()
SPL=collections.defaultdict(list)
for l in open(os.path.join(D,'splits.tsv')):
    t,d,r,_=l.rstrip('\n').split('\t'); SPL[t].append((d,float(r)))
def sfac(t,a,b):
    f=1.0
    for d,r in SPL.get(t,()):
        if a<d<=b: f*=r
    return f
span=lambda p:(dt.date.fromisoformat(p[1])-dt.date.fromisoformat(p[0])).days
CONTRA=('FAIL','q4-input','q4<=0')
def cfdata(t):
    fa=json.load(open(os.path.join(D,'tcf',t+'.json')))['facts']; forms={}
    for node in fa.get('us-gaap',{}).values():
        for arr in node.get('units',{}).values():
            for x in arr: forms.setdefault(x['accn'],(x.get('form',''),x['filed']))
    def pick(tags,unit):
        out={}
        for tag in tags:
            for x in fa.get('us-gaap',{}).get(tag,{}).get('units',{}).get(unit,[]):
                if 'start' in x: out.setdefault((x['accn'],(x['start'],x['end'])),x['val'])
        return out
    WV=pick(('WeightedAverageNumberOfSharesOutstandingBasic','WeightedAverageNumberOfShareOutstandingBasicAndDiluted'),'shares')
    EPS=pick(('EarningsPerShareBasic','EarningsPerShareBasicAndDiluted','IncomeLossFromContinuingOperationsPerBasicShare'),'USD/shares')
    NUMA=pick(('NetIncomeLossAvailableToCommonStockholdersBasic',),'USD'); NUMP=pick(('NetIncomeLoss','ProfitLoss'),'USD')
    dei={x['accn']:x['val'] for x in fa.get('dei',{}).get('EntityCommonStockSharesOutstanding',{}).get('units',{}).get('shares',[])}
    return forms,WV,EPS,NUMA,NUMP,dei
def cfclose(a,P,v,EPS,NUMA,NUMP):
    e=EPS.get((a,P))
    if (a,P) in NUMA: return closes(v,e,NUMA[(a,P)])
    x=NUMP.get((a,P))
    return closes(v,e,x,0.005,TAUC*abs(x)) if x is not None else None
by=collections.defaultdict(list)
for g,t,c,a,f in todo: by[(t,c)].append((f,a))
res={}; Q4SRC=collections.Counter(); KIND=collections.Counter()
for (t,c),fl in sorted(by.items()):
    forms,WV,EPS,NUMA,NUMP,dei=cfdata(t)
    mine={a for f,a in fl}; first=min(f for f,a in fl)
    ev=sorted([(f,a,'T') for f,a in set(fl)]+[(fd,a,'d') for a,(fm,fd) in forms.items() if fm in('10-K','10-Q','20-F','40-F') and a not in mine and a in dei])
    # 10-Q 값 표(자기 공시 시점): accn -> {'3m':(P,v,ok), '9m':(P,v,ok)}
    qv={}
    for a,(fm,fd) in forms.items():
        if not fm.startswith('10-Q'): continue
        for key,lo,hi in (('3m',80,100),('9m',255,285)):
            ps=[p for (aa,p) in WV if aa==a and lo<span(p)<hi]
            if ps:
                P=max(ps,key=lambda p:p[1]); v=WV[(a,P)]
                qv.setdefault(a,{})[key]=(fd,P,v,(cfclose(a,P,v,EPS,NUMA,NUMP) if v else False) is not False,'cf')
    st=[]; prev=None
    for f,a,kind in ev:
        form=forms.get(a,('10-Q',f))[0]
        if kind=='d':
            st.append((f,'O','d')); prev=(f,dei[a]); continue
        try: F,cover=load(os.path.join(D,'tinst',f'{c}_{a}.xml'))
        except Exception: st.append((f,'X','noinst')); continue
        n,k,P=rule6.rule(t,F,cover,form,dei.get(a))
        fpi=form.startswith(('20-F','40-F'))
        if fpi and n is not None and k.startswith('B'): n,k=None,'20F-class'
        if n is None: st.append((f,'X',k)); continue
        if form.startswith('10-Q'):
            r9=rule6.rule(t,F,cover,'9M',dei.get(a)); d={'3m':(f,P,n,True,'inst')}
            if r9[2]: d['9m']=(f,r9[2],r9[0],r9[0] is not None,'inst')
            qv[a]=d
        em={m:v for tg in ETAGS for (m,p),v in F[tg].items() if p==P and m!='-'}
        wm={m for tg in WTAGS for (m,p) in F[tg] if p==P and m!='-'}
        cv={m:float(v) for m,v in cover.get('EntityCommonStockSharesOutstanding',{}).items() if m!='-' and float(v)>0}
        S=set(em) or wm; N=None
        if not fpi and S and S<=set(cv):
            if not em or len({round(v,4) for v in em.values()})==1: N=sum(cv[m] for m in S)
            else:
                Lm,_=lpmember(t,cover,list(em))
                if Lm and em[Lm]: N=sum(cv[m]*em[m] for m in S)/em[Lm]
            if N is not None: KIND['P-ab']+=1
        nq=n
        if N is None and not form.startswith('10-Q') and not fpi:
            ys,ye=P; q9=None
            lo=str(dt.date.fromisoformat(ys)-dt.timedelta(days=7)); hi=str(dt.date.fromisoformat(ys)+dt.timedelta(days=7)); cut=str(dt.date.fromisoformat(ye)-dt.timedelta(days=20))
            for aa,dd in qv.items():
                x=dd.get('9m')
                if x and x[0]<f and lo<=x[1][0]<=hi and x[1][1]<cut and (q9 is None or x[1][1]>q9[1][1]): q9=x
            byp={}
            for aa,dd in qv.items():
                x=dd.get('3m')
                if x and x[0]<f and x[1][0]>=str(dt.date.fromisoformat(ys)-dt.timedelta(days=7)) and x[1][1]<str(dt.date.fromisoformat(ye)-dt.timedelta(days=20)):
                    byp.setdefault(x[1],x)
            tot=span(P)+1
            if q9 and q9[2]:
                if not q9[3]: st.append((f,'X','q4-input-FAIL')); continue
                d9=span(q9[1])+1; nq=(n*tot-q9[2]*sfac(t,q9[0],f)*d9)/(tot-d9); Q4SRC['9m-'+q9[4]]+=1
            elif len(byp)==3:
                if any(not x[3] for x in byp.values()): st.append((f,'X','q4-input-FAIL')); continue
                nq=(n*tot-sum(x[2]*sfac(t,x[0],f)*(span(p)+1) for p,x in byp.items()))/(tot-sum(span(p)+1 for p in byp)); Q4SRC['3x3m']+=1
            else: st.append((f,'X','no-q4')); continue
            if nq<=0: st.append((f,'X','q4<=0')); continue
        if N is None and cv and not fpi and abs(sum(cv.values())/nq-1)<=TAUC_PT: N=sum(cv.values()); KIND['P-c']+=1
        if N is not None:
            st.append((f,'O','P')); prev=(f,N); continue
        KIND['avg']+=1
        if prev is None: st.append((f,'X','no-prev')); prev=(f,nq); continue   # 첫 앵커 가드: 비교할 앞 값 없는 평균은 받지 않는다(상장 분기 CRCL 107.5 대 시점 235.5)
        if prev and abs(nq/(prev[1]*sfac(t,prev[0],f))-1)>TAU:
            st.append((f,'X',f'jump {prev[1]/1e6:.1f}->{nq/1e6:.1f}M')); prev=(f,nq); continue
        st.append((f,'O',k)); prev=(f,nq)
    res[t]=st
# 그날까지 공시된 판만 보는 숨김·제외, 창 [W, END] 의 평일 수로 센다
def weekdays(a,b):
    a=dt.date.fromisoformat(a); b=dt.date.fromisoformat(b); n=0
    while a<b:
        if a.weekday()<5: n+=1
        a+=dt.timedelta(days=1)
    return n
hid=collections.Counter(); exc={}; flick=collections.Counter(); hidden_by=collections.defaultdict(int)
for t,st in res.items():
    st=sorted(st)
    # 어긋남 구간 시작일: 어긋남 판이 직전 판(통과)이 아닌 실패 뒤가 아닐 때
    starts=[]; prevX=False
    for d,s,k in st:
        isC=s=='X' and any(x in k for x in CONTRA)
        if isC and not prevX: starts.append(d)
        prevX=(s=='X')
    # d일 제외 ⇔ (d−L, d] 안에 어긋남 구간 시작이 둘 이상. 구간: [starts[i], starts[i-1]+L)
    EXI=[]
    for i in range(1,len(starts)):
        a0=dt.date.fromisoformat(starts[i]); e0=dt.date.fromisoformat(starts[i-1])+L
        if a0<e0: EXI.append((starts[i],str(e0)))
    if any(a<=END and b>W for a,b in EXI): exc[t]=[(a,b) for a,b in EXI if a<=END and b>W]
    def excluded(d): return any(a<=d<b for a,b in EXI)
    seen=False; shown=None
    for i,(d,s,k) in enumerate(st):
        nxt=st[i+1][0] if i+1<len(st) else END
        a,b=max(d,W),min(nxt,END)
        if a<b and s=='X' and not excluded(d):
            cat='어긋남' if any(x in k for x in CONTRA) else ('전이' if k.startswith('jump') else '원장이 안 줌')
            hid[cat]+=weekdays(a,b); hidden_by[t]+=weekdays(a,b)
        if d>=W and d<=END and s=='X' and seen: flick[t]+=1
        if s=='O' and d<=END: seen=True
    for x,y in EXI:
        a,b=max(x,W),min(y,END)
        if a<b: hid['발행사 제외']+=weekdays(a,b)
print('τ',TAU,'τc',TAUC_PT,'발행사',len(res),'| 숨김 종목·평일(창)',dict(hid),'| 발행사 제외',len(exc),'| 값 갈래',dict(KIND),'| Q4',dict(Q4SRC))
for t,d in sorted(exc.items()): print('  제외',t,'구간',d,[x for x in res[t] if x[1]=='X' and any(c in x[2] for c in CONTRA)][:3])
inwin={t:v for t,v in hidden_by.items() if v>0 and t not in exc}
print('판 숨김이 창 안에 있는 발행사',len(inwin))
for t,v in sorted(inwin.items(),key=lambda x:-x[1]): print('  ',t,v,'평일 · 보인 뒤 숨음',flick.get(t,0),[x for x in res[t] if x[1]=='X' and x[0]>=W][:3])

if os.environ.get('OUTJSON'): json.dump(res,open(os.environ['OUTJSON'],'w'))
