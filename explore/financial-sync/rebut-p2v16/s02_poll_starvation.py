# s01의 대기를 폴링 간격 P로 다시: 사슬은 P마다 잠금을 시도 · 그 사이 시작한 손 실행이 먼저 쥔다(대기 중 사슬에 우선권 없음 — v16 글자)
import csv, datetime as dt
rows=[]
for r in csv.DictReader(open('runs60.csv')):
    if not r['id'] or not r['s']: continue
    s=dt.datetime.fromisoformat(r['s']); f=dt.datetime.fromisoformat(r['f']) if r['f'] else s
    rows.append(dict(id=int(r['id']),job=r['job_name'],tb=r['triggered_by'],s=s,f=f))
hand=[r for r in rows if r['tb'] in ('MANUAL','ADMIN')]
days=sorted({r['s'].date() for r in rows})
for P in (5,60):
    print(f'== poll {P}s')
    d=days[0]
    while d<=days[-1]:
        if d.weekday()<5:
            t=dt.datetime.combine(d,dt.time(13,30)); now=t; grabbed=[]
            while now< dt.datetime.combine(d,dt.time(23,0)):
                live=[r for r in hand if r['s']<=now<r['f']]
                if not live: break
                if now>t: grabbed+= [x['id'] for x in live if x['s']>t]
                now+=dt.timedelta(seconds=P)
            if now>t:
                g=sorted(set(grabbed))
                print(f"  {d} chain starts {now:%H:%M:%S} (wait {(now-t).total_seconds()/60:.0f}m) · hand runs that started during the wait and got the lock first: {len(g)} {g[:12]}")
        d+=dt.timedelta(days=1)
