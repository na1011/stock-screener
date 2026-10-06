# v8 결정 1·3: 한 메서드 안에서 「그날 무거운 잡」을 두 번 묻는가(예약 계산 = 단계 시작 · 선택 = 일일 갱신 뒤)?
# PipelineScheduler.isOverdue = JobFreshnessPolicy.daysSinceLastSuccess(job, Instant.now()) > 25
#   = Duration.between(max(started_at), now).toDays() > 25   (toDays는 0 쪽으로 자른다)
# heavyJobDueToday: 배정 요일(화 G · 수 FSR · 목 FR)이 연체면 그것, 아니면 연체 중 가장 오래 밀린 것.
# 0콜 · DB 안 읽음. 시작 기록(운영 jar · 2부 전)은 progress ⓪의 첫 정기 G 10-19 · FSR 10-20 · FR 10-30(2부 jar)를 따른다.
import datetime as dt, random
KST = dt.timezone(dt.timedelta(hours=9))
def at(d, hh, mm, ss=0): return dt.datetime(d.year, d.month, d.day, hh, mm, ss, tzinfo=KST)
def days(last, now): return int((now - last).total_seconds() // 86400) if now >= last else -int((last-now).total_seconds()//86400)
ASSIGN = {1: 'G', 2: 'FSR', 3: 'FR'}   # weekday(): 월0 화1 수2 목3
def due(last, now):
    od = {j: days(last[j], now) for j in last if days(last[j], now) > 25}
    a = ASSIGN.get(now.weekday())
    if a in od: return a
    return max(od, key=lambda j: od[j]) if od else None

# 1) 손으로 만든 반례 — 같은 날 두 시점
last = {'G': at(dt.date(2026,10,19),14,15,3), 'FSR': at(dt.date(2026,10,20),14,15,2), 'FR': at(dt.date(2026,10,30),14,2,0)}
d = dt.date(2026,11,25)  # 수 · FR + 26일
print('반례: FR 마지막 시작', last['FR'].strftime('%m-%d %H:%M'), '· 11-25(수)')
for label, t in [('단계 시작(예약 계산) 13:30:05', at(d,13,30,5)), ('선택(단계 5분+갱신 24분 뒤) 13:59:05', at(d,13,59,5)), ('선택(단계 20분+갱신 24분 뒤) 14:14:05', at(d,14,14,5))]:
    print(f'  {label}: FR 경과 toDays={days(last["FR"], t)} → 그날 무거운 잡={due(last, t)}')

# 2) 달력 모의 — 2부 시대(10-27 ~ 2027-10-29) · 하루마다 예약 시점 13:30:05와 선택 시점(13:30 + 단계 + 갱신)의 답을 비교
random.seed(20261006)
PEAK_DEADLINES = [dt.date(2026,11,16), dt.date(2027,3,31), dt.date(2027,5,17), dt.date(2027,8,16)]
def next_weekdays(d, n):
    out=[]; x=d
    while len(out)<n:
        x += dt.timedelta(days=1)
        if x.weekday()<5: out.append(x)
    return out
PEAK_DAYS = {x for p in PEAK_DEADLINES for x in next_weekdays(p, 2)}
diverge=[]; d=dt.date(2026,10,27)
while d <= dt.date(2027,10,29):
    if d.weekday()<5:
        stage = random.uniform(15,42) if d in PEAK_DAYS else random.uniform(1,6)
        daily = random.uniform(15,24)
        t_res = at(d,13,30,5); t_sel = t_res + dt.timedelta(minutes=stage+daily)
        a, b = due(last, t_res), due(last, t_sel)
        if a != b: diverge.append((d, a, b, d in PEAK_DAYS, round(stage)))
        if b: last[b] = t_sel   # started_at = 선택 시각
    d += dt.timedelta(days=1)
print(f'\n2부 시대 1년 평일 중 「예약 시점 답 ≠ 선택 시점 답」 {len(diverge)}일 (예약 없이 무거운 잡이 돈 날):')
for x in diverge: print('  ', x[0], x[0].strftime('%a'), '예약 시점', x[1], '→ 선택 시점', x[2], '· 몰린 날' if x[3] else '', f'· 단계 {x[4]}분')
