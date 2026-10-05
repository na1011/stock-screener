# 0콜: PipelineScheduler.heavyJobDueToday(:289-309) + HEAVY_JOB_MIN_INTERVAL_DAYS=25(:58) + Duration.toDays 재현
# 입력 = 운영 batch_execution 현 술어 시계(q01_clock.out — NULL 이력을 예전처럼 세면 그대로)
from datetime import datetime, timedelta, timezone
KST=timezone(timedelta(hours=9))
last={'G':datetime(2026,9,20,16,14,41,tzinfo=KST),   # #633 MANUAL 337
      'FSR':datetime(2026,9,23,0,26,25,tzinfo=KST),  # #664 MANUAL 129
      'FR':datetime(2026,10,3,21,17,41,tzinfo=KST)}  # #710 MANUAL 1(IOVA)
assign={1:'G',2:'FSR',3:'FR'}  # Tue Wed Thu (weekday(): Mon=0)
def days(j,now): return int((now-last[j]).total_seconds()//86400)
d=datetime(2026,10,6,14,15,tzinfo=KST)
out=[]
while d < datetime(2026,12,10,tzinfo=KST):
    if d.weekday()<5:
        a=assign.get(d.weekday()); due=None
        if a and days(a,d)>25: due=a
        else:
            od=[j for j in last if days(j,d)>25]
            if od: due=max(od,key=lambda j:days(j,d))  # 동률이면 Map.of 순서(미정) — 출력에 표시
            if od and len(od)>1:
                ds=sorted(days(j,d) for j in od)
                if ds[-1]==ds[-2]: out.append(f"  동률 {d.date()} {[(j,days(j,d)) for j in od]}")
        if due:
            out.append(f"{d.date()} {d.strftime('%a')} → {due} (경과 {days(due,d)}일 · 그 밖 연체 {[(j,days(j,d)) for j in last if j!=due and days(j,d)>25]})")
            last[due]=d
    d+=timedelta(days=1)
print("\n".join(out))
