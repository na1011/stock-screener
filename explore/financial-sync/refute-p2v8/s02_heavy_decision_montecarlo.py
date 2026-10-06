# s01의 달력 모의를 씨앗 500개로 — 예약 시점(13:30:05)과 선택 시점(13:30 + 단계 + 갱신)의 답이 갈리는 날의 수.
# 단계: 몰린 날(마감 다음 두 평일) 15~42분 · 그 밖 1~6분 · 갱신 15~24분(최근 실측 13:35~23:51 · q02).
exec(open('s01_heavy_decision_instants.py').read().split('# 1)')[0])
import random, collections
PEAK_DEADLINES = [dt.date(2026,11,16), dt.date(2027,3,31), dt.date(2027,5,17), dt.date(2027,8,16)]
def next_weekdays(d, n):
    out=[]; x=d
    while len(out)<n:
        x += dt.timedelta(days=1)
        if x.weekday()<5: out.append(x)
    return out
PEAK_DAYS = {x for p in PEAK_DEADLINES for x in next_weekdays(p, 2)}
tot=[]; peak=0; jobs=collections.Counter(); ex=None
for seed in range(500):
    random.seed(seed)
    last = {'G': at(dt.date(2026,10,19),14,15,3), 'FSR': at(dt.date(2026,10,20),14,15,2), 'FR': at(dt.date(2026,10,30),13,30,5)+dt.timedelta(minutes=random.uniform(16,30))}
    n=0; d=dt.date(2026,10,27)
    while d <= dt.date(2027,10,26):
        if d.weekday()<5:
            stage = random.uniform(15,42) if d in PEAK_DAYS else random.uniform(1,6)
            t_res = at(d,13,30,5); t_sel = t_res + dt.timedelta(minutes=stage+random.uniform(15,24))
            a,b = due(last,t_res), due(last,t_sel)
            if a!=b:
                n+=1; jobs[(a,b)]+=1
                if d in PEAK_DAYS: peak+=1; ex = ex or (seed, d, a, b)
            if b: last[b]=t_sel
        d+=dt.timedelta(days=1)
    tot.append(n)
print('씨앗 500 · 1년(2026-10-27~2027-10-26) 평일')
print('  갈리는 날 수/년: 평균 %.2f · 최소 %d · 최대 %d' % (sum(tot)/len(tot), min(tot), max(tot)))
print('  갈림 꼴(예약 시점 → 선택 시점):', dict(jobs))
print('  그중 몰린 날(마감 다음 두 평일) 총 %d회 / 500년 · 예:' % peak, ex)

# 갈리는 날의 달 분포 · 3월 사업보고서 철(03-10~04-02)을 단계 10~30분으로 넓혀 다시
months=collections.Counter(); march=0
def heavy_stage(d):
    if d in PEAK_DAYS: return random.uniform(15,42)
    if dt.date(2027,3,10) <= d <= dt.date(2027,4,2): return random.uniform(10,30)
    return random.uniform(1,6)
for seed in range(500):
    random.seed(seed)
    last = {'G': at(dt.date(2026,10,19),14,15,3), 'FSR': at(dt.date(2026,10,20),14,15,2), 'FR': at(dt.date(2026,10,30),13,30,5)+dt.timedelta(minutes=random.uniform(16,30))}
    d=dt.date(2026,10,27)
    while d <= dt.date(2027,10,26):
        if d.weekday()<5:
            stage = heavy_stage(d)
            t_res = at(d,13,30,5); t_sel = t_res + dt.timedelta(minutes=stage+random.uniform(15,24))
            a,b = due(last,t_res), due(last,t_sel)
            if a!=b:
                months[d.strftime('%Y-%m')]+=1
                if dt.date(2027,3,10) <= d <= dt.date(2027,4,2) or d in PEAK_DAYS: march+=1
            if b: last[b]=t_sel
        d+=dt.timedelta(days=1)
print('\n3월 철을 넓힌 모형: 갈림 달 분포', dict(sorted(months.items())), '· 단계가 무거운 날과 겹침', march, '/ 500년')
