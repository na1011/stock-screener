# 0콜: 저장된 EDGAR daily-index/2025/QTR4/index.json(rebut-p2v1/sec · 10-06 08:28 받음)의 last-modified(ET)를 KST로 바꿔
# 「D의 색인이 D+1 몇 시(KST)에 마지막으로 바뀌었나」를 센다. last-modified는 마지막 수정이라 첫 게시의 상한이다.
import json, datetime as dt, collections
from zoneinfo import ZoneInfo
ET=ZoneInfo('America/New_York'); KST=ZoneInfo('Asia/Seoul')
d=json.load(open('rebut-p2v1/sec/index-2025QTR4.json'))
rows=collections.defaultdict(list)
for it in d['directory']['item']:
    n=it['name']
    if not n.endswith('.idx'): continue
    kind,date=n.split('.')[0],n.split('.')[1]
    lm=dt.datetime.strptime(it['last-modified'],'%m/%d/%Y %I:%M:%S %p').replace(tzinfo=ET)
    k=lm.astimezone(KST)
    fd=dt.date(int(date[:4]),int(date[4:6]),int(date[6:]))
    rows[kind].append((fd,lm,k))
for kind in sorted(rows):
    r=sorted(rows[kind])
    def after(h,m,season):
        sel=[x for x in r if (x[1].utcoffset()==dt.timedelta(hours=-5))==(season=='EST')]
        late=[x for x in sel if (x[2].date()-x[0]).days>1 or ((x[2].date()-x[0]).days==1 and (x[2].hour,x[2].minute)>(h,m))]
        return len(sel),len(late),[(str(x[0]),x[2].strftime('%m-%d %H:%M')) for x in late]
    print(kind, len(r))
    for season in ('EDT','EST'):
        for h,m in ((12,0),(12,30),(13,0)):
            n,l,lst=after(h,m,season)
            print(f'  {season} n={n} KST>D+1 {h:02d}:{m:02d}: {l}', lst[:12])
    # 분포
    for season in ('EDT','EST'):
        sel=[x for x in r if (x[1].utcoffset()==dt.timedelta(hours=-5))==(season=='EST')]
        ts=sorted(x[2].strftime('%H:%M') if (x[2].date()-x[0]).days==1 else 'D+%d %s'%((x[2].date()-x[0]).days,x[2].strftime('%H:%M')) for x in sel)
        print('  ',season,'KST min/median/max:',ts[0] if ts else None, ts[len(ts)//2] if ts else None, ts[-1] if ts else None)
