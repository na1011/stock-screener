# v16 결정 1 잠금을 60일 운영 이력(runs60.csv)에 재생한다.
# 규칙(계획 글자): 사슬은 평일 13:30에 잠금을 원함 · 쥔 자가 있으면 기다림(23:00 상한) · 기다리는 동안 잠금은 비어 있지 않음(손 실행이 쥠)
#   bootRun(MANUAL)·ADMIN은 사슬이 쥐고 있으면 거절 · 다른 손 실행이 쥐고 있을 때는 계획 글자 미정 → 두 해석(A: 손끼리 자유(세션 공유) · B: 손끼리도 거절)
# 사슬 길이 = 그날 SCHEDULER 행(13:00~16:00 시작)의 첫 시작~마지막 끝(역사적 13:30~14:20대 크론 합) · 없으면 60분 가정(표시)
# 「기아」 = 사슬이 기다리는 동안 새 손 실행이 시작해 잠금을 먼저 쥔 경우(대기 중 사슬은 우선권이 없다 — 계획에 우선권 글 없음)
import csv, datetime as dt
from collections import defaultdict
rows=[]
for r in csv.DictReader(open('runs60.csv')):
    if not r['id'] or not r['s']: continue
    s=dt.datetime.fromisoformat(r['s']); f=dt.datetime.fromisoformat(r['f']) if r['f'] else s
    rows.append(dict(id=int(r['id']),job=r['job_name'],tb=r['triggered_by'],st=r['status'],s=s,f=f))
hand=[r for r in rows if r['tb'] in ('MANUAL','ADMIN')]
sched=[r for r in rows if r['tb']=='SCHEDULER']
days=sorted({r['s'].date() for r in rows})
d0,d1=days[0],days[-1]
out=[]
tot=dict(days=0,wait_days=0,wait_min=0,starve_days=0,reject_runs=0,reject_days=0,over23=0,nochain=0)
waits=[]
d=d0
while d<=d1:
    if d.weekday()<5:
        tot['days']+=1
        t=dt.datetime.combine(d,dt.time(13,30))
        sc=[r for r in sched if r['s'].date()==d and 13<=r['s'].hour<16 and r['job']!='universe-rebalance']
        if sc:
            dur=max(r['f'] for r in sc)-min(r['s'] for r in sc)
        else:
            dur=dt.timedelta(minutes=60); tot['nochain']+=1
        # 대기: 13:30에 살아 있는 손 실행 또는 대기 중 새로 시작한 손 실행이 잠금을 쥔다
        start=t; starved=False; holders=[]
        while True:
            live=[r for r in hand if r['s']<=start< r['f']]
            if not live: break
            if start>t: pass
            holders+= [x['id'] for x in live]
            nxt=max(r['f'] for r in live)
            # 대기 구간에 새로 시작한 손 실행(기아)
            newcomers=[r for r in hand if start< r['s'] <nxt]
            if newcomers and start>=t: 
                pass
            start=nxt
            # 끝난 순간 바로 이어 시작한 실행이 있으면 그것이 먼저 쥘 수 있다(같은 초 이내는 경합 · 여기선 다음 루프의 live가 잡음)
        # 기아: 대기 창(t,start) 안에 시작한 손 실행
        starters=[r for r in hand if t< r['s'] < start]
        if starters: starved=True
        if start>t:
            tot['wait_days']+=1; m=(start-t).total_seconds()/60; tot['wait_min']+=m; waits.append((d,round(m),holders[:6]))
        if starved: tot['starve_days']+=1
        if start>=dt.datetime.combine(d,dt.time(23,0)): tot['over23']+=1
        end=start+dur
        rej=[r for r in hand if start<= r['s'] < end]
        if rej:
            tot['reject_days']+=1; tot['reject_runs']+=len(rej)
            out.append(f"{d} chain {start:%H:%M}-{end:%H:%M} reject {[(r['id'],r['job'],r['tb'],r['s'].strftime('%H:%M')) for r in rej]}")
    d+=dt.timedelta(days=1)
print(tot)
print('waits(day,min,holders):')
for w in waits: print(' ',w)
print('rejections:')
for o in out: print(' ',o)
