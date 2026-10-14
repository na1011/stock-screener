# 롤포워드 참여 확인의 문턱 실측: 층 a(구성원별 EPS = 참여 집합을 원장이 말함)를 정답 표본으로,
# Σ_{k∈S}(기초_k+기말_k)/2 ÷ n 의 분포(10-Q 3개월 · 10-K 12개월 따로)와, 그 문턱에서 다른 부분집합도 맞는 비율.
import json,os,sys,collections,datetime as dt,itertools,statistics
import xml.etree.ElementTree as ET
D=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,D)
import rule6
from rule6 import load,ETAGS
XI='http://www.xbrl.org/2003/instance'; XD='http://xbrl.org/2006/xbrldi'; AX='us-gaap:StatementClassOfStockAxis'
TAGS=('CommonStockSharesOutstanding','SharesOutstanding')
def roll(p):
    r=ET.parse(p).getroot(); ctx={}
    for c in r.iter('{%s}context'%XI):
        d={e.get('dimension'):e.text for e in c.iter('{%s}explicitMember'%XD)}
        per=c.find('{%s}period'%XI); ctx[c.get('id')]=(d,tuple(y.text for y in per))
    out=collections.defaultdict(dict)
    for e in r:
        t=e.tag.split('}')[-1]
        if t not in TAGS: continue
        cx=ctx.get(e.get('contextRef'))
        if not cx or len(cx[1])!=1 or AX not in cx[0] or set(cx[0])-{AX,'us-gaap:StatementEquityComponentsAxis'}: continue
        try: out[cx[0][AX]].setdefault(cx[1][0],float(e.text))
        except: pass
    return out
def mids(p,P):
    b=roll(p); s0=str(dt.date.fromisoformat(P[0])-dt.timedelta(days=1)); e0=P[1]; m={}
    for mem,vals in b.items():
        bb=vals.get(s0, vals.get(P[0])); ee=vals.get(e0)
        if bb is not None and ee is not None and (bb>0 or ee>0): m[mem]=(bb+ee)/2
    return m
if __name__=='__main__':
    todo=json.load(open('pop_todo.json'))+json.load(open('target_todo.json'))
    R={'Q':[], 'K':[]}; AMB={'Q':[], 'K':[]}
    for g,t,c,a,f in todo:
        p=f'tinst/{c}_{a}.xml'
        if not os.path.exists(p): continue
        F,cover=load(p)
        for form,key in (('10-Q','Q'),('10-K','K')):
            n,k,P=rule6.rule(t,F,cover,form)
            if P: break
        if n is None: continue
        em={m:v for tg in ETAGS for (m,pp),v in F[tg].items() if pp==P and m!='-'}
        if not em or len({round(v,4) for v in em.values()})!=1: continue   # 같은 EPS일 때만: 환산비 1
        M=mids(p,P)
        if not set(em)<=set(M): continue
        r=sum(M[m] for m in em)/n; R[key].append((abs(r-1),t,f))
        AMB[key].append((t,f,{tuple(sorted(S)) :abs(sum(M[m] for m in S)/n-1) for L_ in range(1,len(M)+1) for S in itertools.combinations(M,L_)},tuple(sorted(em))))
    for key in R:
        xs=sorted(x[0] for x in R[key])
        if not xs: continue
        q=lambda z: xs[min(len(xs)-1,int(z*len(xs)))]
        print(key,'n',len(xs),'p50 %.4f p90 %.4f p95 %.4f p98 %.4f'%(q(.5),q(.9),q(.95),q(.98)))
        tau=q(.95)
        ok=wrong=amb=none=0
        for t,f,fits,S in AMB[key]:
            hits=[s for s,d in fits.items() if d<=tau]
            if len(hits)==1: ok+= hits[0]==S; wrong+= hits[0]!=S
            elif len(hits)>1: amb+=1
            else: none+=1
        print('   τ_rf=p95: 유일·정답',ok,'유일·오답',wrong,'모호',amb,'없음',none)
