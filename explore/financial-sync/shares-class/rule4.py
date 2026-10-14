# rule4 (10-14 밤): 폐합을 「후보 중 분자에 가장 가까운 것 + 축척 띠」 하나로 짠다.
# 근거: resid.py — n·e/분자 의 정당한 퍼짐(참여 증권 배분·비지배·우선주)은 |log10| ≤ 0.95, 축척 오태깅은 ≥ 2.0(0.001배 71 · 100배 WSE).
# 띠 BAND=1.5는 그 빈 구간 안 어디여도 답이 같다. 부호가 어긋나거나 e·분자가 없으면 검사 불가(첫 후보를 받는다).
import math
from rule2 import load,lpmember,WTAGS,ETAGS,NTAGS
BAND=1.5
def rule(ticker,F,cover,form):
    import datetime as dt
    pers=set(p for t in WTAGS for (m,p) in F[t])
    if not pers: return None,'no-wavg',None
    span=lambda p:(dt.date.fromisoformat(p[1])-dt.date.fromisoformat(p[0])).days
    want=(80,100) if form.startswith('10-Q') else (350,380)
    cand=[p for p in pers if want[0]<span(p)<want[1]]
    if not cand: return None,'no-own-period',None
    P=max(cand,key=lambda p:p[1])
    w={};e={}
    for t in WTAGS:
        for (m,p),v in F[t].items():
            if p==P and m not in w: w[m]=v
    for t in ETAGS:
        for (m,p),v in F[t].items():
            if p==P and m not in e: e[m]=v
    num=next((F[t][('-',P)] for t in NTAGS if ('-',P) in F[t]),None)
    edm=[m for m in e if m!='-']
    alloc=None
    for t in NTAGS[:2]:
        if edm and all((m,P) in F[t] for m in edm): alloc=sum(F[t][(m,P)] for m in edm); break
    # 후보: (이름, n, n·e_L 꼴의 좌변, 비교할 분자)
    C=[]
    if '-' in w:
        eL=e.get('-') if '-' in e else (e[edm[0]] if len(edm)==1 else None)
        C.append(('A',w['-'],None if eL is None else w['-']*eL,num))
    dims=[m for m in w if m!='-']
    if dims and '-' in e and not edm:
        C.append(('M',sum(w[m] for m in dims),sum(w[m] for m in dims)*e['-'],num))
        for m in dims: C.append(('M1',w[m],w[m]*e['-'],num))
    eds={m:e[m] for m in dims if m in e}
    if eds:
        N=alloc if alloc is not None else num
        lhs=sum(w[m]*eds[m] for m in eds)
        if len({round(v,4) for v in eds.values()})==1: C.append(('B-equal',sum(w[m] for m in eds),lhs,N))
        Lm,how=lpmember(ticker,cover,list(eds))
        if Lm and eds[Lm]:
            C.append(('B-sum/'+how,lhs/eds[Lm],lhs,N))
            C.append(('B-member/'+how,w[Lm],w[Lm]*eds[Lm],num))
    if not C:
        if dims and not e: return sum(w[m] for m in dims),'M0-unverifiable',P
        return None,('B-unmapped' if eds else 'no-rule'),P
    def dev(c):
        _,n,l,x=c
        if l is None or not x or not l or (l>0)!=(x>0): return None
        return abs(math.log10(l/x))
    ver=[(dev(c),c) for c in C if dev(c) is not None]
    if not ver: return C[0][1],C[0][0].split('/')[0]+'-unverifiable',P
    d,c=min(ver,key=lambda z:z[0])
    if d>=BAND: return None,c[0].split('/')[0]+'-FAIL',P
    return c[1],c[0].split('/')[0]+'-ok',P
