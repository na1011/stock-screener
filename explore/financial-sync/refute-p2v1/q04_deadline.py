# 2부 활성일 X별 초과 지연(라벨-일) — 2025 같은 철 첫 공시일을 달력 날짜 그대로 2026으로 옮김(+365일 · 주말이면 다음 월요일)
# 정기 FR 포착: 10-30(공시일 ≤ 10-29) · 11-25(≤ 11-24) · 12-22(≤ 12-21) — v5 반증의 위상 그대로(재지 않음)
# 단계(X부터 평일마다): 공시일 ≤ d-1인 것을 d에 포착(용량 무시). 초과 지연 = 포착일 - (공시일 다음 평일)
import sys, datetime as dt
D=dt.date
rows=[l.rstrip('\n').split('\t') for l in open(sys.argv[1]) if l.strip() and not l.startswith(('BEGIN','COMMIT'))]
def nextwd(d):
    d=d+dt.timedelta(days=1)
    while d.weekday()>=5: d+=dt.timedelta(days=1)
    return d
def shift(p):
    q=D(2026,p.month,p.day)
    while q.weekday()>=5: q+=dt.timedelta(days=1)
    return q
FR=[(D(2026,10,29),D(2026,10,30)),(D(2026,11,24),D(2026,11,25)),(D(2026,12,21),D(2026,12,22)),(D(2027,1,18),D(2027,1,19))]
def capture(p,X):
    c=[fd for lim,fd in FR if p<=lim]
    best=min(c)
    if X is not None:
        d=max(X,nextwd(p))
        while d.weekday()>=5: d+=dt.timedelta(days=1)
        best=min(best,d)
    return best
for mk in ("US","KR"):
    labs=[]
    for r in rows:
        if r[0]!=mk: continue
        p=D.fromisoformat(r[1]); n=int(r[3])
        labs.append((shift(p),n))
    tot=sum(n for _,n in labs)
    print(mk,"labels",tot)
    for X in [None,D(2026,10,19),D(2026,10,26),D(2026,10,30),D(2026,11,6),D(2026,11,13),D(2026,11,20)]:
        ex=sum(n*(capture(p,X)-nextwd(p)).days for p,n in labs)
        print(" X=",X,"excess_label_days",ex)
