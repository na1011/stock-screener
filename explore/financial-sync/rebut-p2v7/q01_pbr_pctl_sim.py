# 0콜 모의: 몰린 날(2026-08-14) 다음 평일에 한도로 미룬 k종목(보류)이 Q1 자본으로 남을 때,
# 보류되지 않은 종목의 PBR_PCTL(유니버스별 PERCENT_RANK — PercentileCalculator RANKED와 같은 식)이 0.30을 넘나드는 수.
import csv, random, statistics
rows=[r for r in csv.reader(open('q01_rerun.out'),delimiter='\t')][1:]
rows=[r for r in rows if len(r)==6 and r[2]!='']
def pr(vals):  # PERCENT_RANK: (rank-1)/(n-1), 동점은 같은 rank
    s=sorted(vals.values()); n=len(s); out={}
    import bisect
    for k,v in vals.items():
        out[k]=bisect.bisect_left(s,v)/(n-1)
    return out
uni={}
for u,sid,pbr,f,q2,q1 in rows:
    uni.setdefault(u,{})[sid]=(float(pbr), f=='t' and q2!='' and q1!='' and float(q1)!=0, (float(q2)/float(q1)) if (q2 and q1 and float(q1)!=0) else None)
filers=[(u,sid) for u in uni for sid,(p,f,r) in uni[u].items() if f]
print('filers',len(filers),{u:sum(1 for x in filers if x[0]==u) for u in uni})
for k in (40,76):
    flips=[];maxd=[];samsung=0;flip_any_seed=0
    for seed in range(200):
        random.seed(seed); S=set(random.sample(filers,k))
        tot=0
        for u,m in uni.items():
            fresh={sid:p for sid,(p,f,r) in m.items()}
            mixed={sid:(p*r if (u,sid) in S else p) for sid,(p,f,r) in m.items()}
            a=pr(fresh); b=pr(mixed)
            for sid in m:
                if (u,sid) in S: continue
                if (a[sid]<=0.30)!=(b[sid]<=0.30):
                    tot+=1
                    if sid=='151': samsung+=1
                maxd.append(abs(a[sid]-b[sid]))
        flips.append(tot)
    print(f'k={k}: 보류 안 된 종목 중 PBR_PCTL<=0.30이 뒤집히는 수 — 평균 {statistics.mean(flips):.1f} · 최소 {min(flips)} · 최대 {max(flips)} (200 시드) · |Δpct| 최대 {max(maxd):.3f} · p99 {sorted(maxd)[int(len(maxd)*0.99)]:.3f} · 005930 뒤집힘 {samsung}/200')
