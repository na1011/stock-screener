# rule6 = rule5 + 단위 복원: 폐합이 실패할 때 그 기간 분모 사실 전부를 10^k(k=±3,±6)로 옮겨 다시 폐합한다.
# 정확히 한 k만 닫히면 원장의 EPS·분자가 단위를 못 박은 것이다(TEM 178,880 ×1000 · NVMI 30,108 ×1000). 둘 이상·0이면 그대로 실패.
import copy
import rule5
from rule5 import load,lpmember,WTAGS,ETAGS,NTAGS,H
import math
def rule(ticker,F,cover,form,T=None):
    # T: 같은 공시의 시점 총량(표지 합 또는 축 없는 dei) — 분모·EPS 어느 쪽이 단위가 틀렸는지 자릿수로 가른다
    n,k,P=rule5.rule(ticker,F,cover,form)
    if not (P and 'FAIL' in k): return n,k,P
    hits=[]
    for s in (3,6,-3,-6):
        G=copy.copy(F); bak={}
        for t in WTAGS:
            G[t]=dict(F[t])
            for (m,p),v in F[t].items():
                if p==P:
                    G[t][(m,p)]=v*10**s
                    if (t,m,p) in H: bak[(t,m,p)]=H[(t,m,p)]; H[(t,m,p)]=H[(t,m,p)]*10**s
        n2,k2,P2=rule5.rule(ticker,G,cover,form)
        H.update(bak)
        if n2 is not None and 'FAIL' not in k2 and 'unverif' not in k2: hits.append((s,n2,k2))
    for s in (2,3,-2,-3):   # EPS 쪽 단위 오류(센트·천): 분모는 그대로 확인된다
        G=copy.copy(F); bak={}
        for t in ETAGS:
            G[t]=dict(F[t])
            for (m,p),v in F[t].items():
                if p==P:
                    G[t][(m,p)]=v*10**s
                    if (t,m,p) in H: bak[(t,m,p)]=H[(t,m,p)]; H[(t,m,p)]=H[(t,m,p)]*10**s
        n2,k2,P2=rule5.rule(ticker,G,cover,form)
        H.update(bak)
        if n2 is not None and 'FAIL' not in k2 and 'unverif' not in k2: hits.append(('eps'+str(s),n2,k2))
    if len(hits)>1:
        if T is None:
            cv=[float(v) for m,v in cover.get('EntityCommonStockSharesOutstanding',{}).items() if float(v)>0]
            T=sum(cv) if cv else None
        if T: hits=[h for h in hits if abs(math.log10(h[1]/T))<1]
    if len(hits)==1:
        s,n2,k2=hits[0]; return n2,k2+f'+e{s}',P
    return n,k,P
