# v15 결정 4 재생: 사슬은 「운영 DB 손 실행(MANUAL·ADMIN)이 살아 있지 않고 마지막 손 실행 끝에서 10분 지남」일 때 시작(13:30부터 1분 폴링 · 23:00 상한).
# 쓰기 세션 행은 이력에 없으므로 「아무도 begin을 안 쳤다」로 잰다(세션의 효과는 따로 적는다). 사슬 길이 = 그날 실제 SCHEDULER 연쇄 길이(없으면 중앙값).
import csv, datetime as dt, statistics, collections
P=lambda s: dt.datetime.strptime(s,'%Y-%m-%d %H:%M:%S')
rows=[r for r in csv.DictReader(open('runs60.csv')) if r['id'].isdigit()]
for r in rows: r['s']=P(r['s']); r['f']=P(r['f'])
man=[r for r in rows if r['triggered_by'] in ('MANUAL','ADMIN')]
sch=[r for r in rows if r['triggered_by']=='SCHEDULER']
# 손 실행 사이 틈 분포(앞 실행 끝 → 다음 실행 시작 · 같은 연쇄 판정 없이 전수)
man_sorted=sorted(man,key=lambda r:r['s'])
gaps=[]
for a,b in zip(man_sorted,man_sorted[1:]):
    g=(b['s']-max(a['f'],a['s'])).total_seconds(); gaps.append(g)
bk=collections.Counter()
for g in gaps:
    k='<0(겹침)' if g<0 else '<2m' if g<120 else '2-10m' if g<600 else '10-30m' if g<1800 else '30-60m' if g<3600 else '>=60m'
    bk[k]+=1
print('손 실행 연속 틈 분포(n=%d):'%len(gaps), dict(bk))
# 사슬 길이(그날 13:00~18:00에 시작한 SCHEDULER 실행의 첫 시작~마지막 끝)
dur={}
for d in sorted({r['s'].date() for r in sch}):
    xs=[r for r in sch if r['s'].date()==d and 13<=r['s'].hour<18]
    if xs: dur[d]=(max(r['f'] for r in xs)-min(r['s'] for r in xs))
med=statistics.median(dur.values()) if dur else dt.timedelta(minutes=40)
print('사슬 길이 중앙값', med, ' 최대', max(dur.values()))
def live(t): return [r for r in man if r['s']<=t<r['f']]
def last_end_before(t):
    e=[r['f'] for r in man if r['f']<=t]; return max(e) if e else None
d0=dt.date(2026,8,10); d1=dt.date(2026,10,6)
res=[]
d=d0
while d<=d1:
    if d.weekday()<5:
        t=dt.datetime.combine(d,dt.time(13,30)); cap=dt.datetime.combine(d,dt.time(23,0))
        base_live=[r['id'] for r in live(t)]
        start=None
        while t<=cap:
            le=last_end_before(t)
            started_recent=[r for r in man if t-dt.timedelta(minutes=10)<r['s']<=t]
            if not live(t) and not started_recent and (le is None or t-le>=dt.timedelta(minutes=10)):
                start=t; break
            t+=dt.timedelta(minutes=1)
        L=dur.get(d,med)
        coll=[]
        if start:
            coll=[r for r in man if start<=r['s']<start+L]
        # 지금 main(13:30 고정): 13:30에 살아 있거나 사슬 동안 시작한 손 실행
        t0=dt.datetime.combine(d,dt.time(13,30)); L0=dur.get(d,med)
        base_coll=[r['id'] for r in man if (r['s']<=t0<r['f']) or (t0<=r['s']<t0+L0)]
        res.append((d,start,L,coll,base_coll))
    d+=dt.timedelta(days=1)
print('\n평일 %d일'%len(res))
delayed=[x for x in res if x[1] and x[1]>dt.datetime.combine(x[0],dt.time(13,30))]
missed=[x for x in res if not x[1]]
collided=[x for x in res if x[3]]
basecoll=[x for x in res if x[4]]
print('13:30보다 늦게 시작한 날', len(delayed), ' 23:00까지 못 시작(미룸)', len(missed), ' v15 규칙에서도 사슬 도중 손 실행이 시작한 날', len(collided), ' 지금 main(13:30 고정)에서 겹친 날', len(basecoll))
for d,s,L,c,b in res:
    if (s and s.time()!=dt.time(13,30)) or not s or c or b:
        print(d, d.strftime('%a'), '시작', s.strftime('%H:%M') if s else '미룸(23:00)', '길이', str(L)[:7], '| v15 겹침', [(r['id'],r['job_name'],r['s'].strftime('%H:%M')) for r in c], '| main 겹침 id', b)
