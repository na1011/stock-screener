# 0콜: DartIrdsReports.covering(from, to, today)를 그대로 옮겨 국내 기업 행위 사슬의 종목당 irdsSttus 콜 수를 센다.
# 상수(main): EARLIEST_BUSINESS_YEAR=2015 · ANNUAL_HISTORY_YEARS=5 · QUARTERLY_HISTORY_YEARS=3 · from = 시장 최초 봉(2020-01-02).
import datetime as dt
D=dt.date
def candidates(f,t):
    out=[]
    for y in range(max(f.year,2015), t.year+1):
        af=D(y-5+1,1,1); qf=D(y-3+1,1,1)
        out+= [(y,'11011',af,D(y,12,31),D(y+1,3,31)),(y,'11013',qf,D(y,3,31),D(y,5,15)),
               (y,'11012',qf,D(y,6,30),D(y,8,14)),(y,'11014',qf,D(y,9,30),D(y,11,14))]
    return out
def covering(f,t,today):
    av=[r for r in candidates(f,t) if today>=r[4] and not r[3]<f and not r[2]>t]
    ch=[];pos=f
    while pos<=t:
        reach=[r for r in av if not r[3]<pos]
        if not reach: break
        here=[r for r in reach if not r[2]>pos]
        if not here: pos=min(r[2] for r in reach); continue
        b=max(here,key=lambda r:r[3]); ch.append(b); av.remove(b); pos=b[3]+dt.timedelta(days=1)
    return ch
for today in (D(2026,10,6),D(2026,11,17),D(2027,2,1)):
    asof=today-dt.timedelta(days=1)
    c=covering(D(2020,1,2),asof,today)
    print(today, len(c), [(r[0],r[1]) for r in c], '× 국내 350 =', len(c)*350)
