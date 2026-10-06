# DartIrdsReports.covering(from, to, today)를 그대로 옮겨 국내 사슬 창(from = 국내 최초 봉)에서 종목당 irdsSttus 콜 수를 날짜마다 센다.
# from은 보존 창이 굴러가며 바뀔 수 있다 — 지금 값(2020-01-02 · q05)과 1년 뒤로 민 값 둘을 본다.
from datetime import date, timedelta
def cands(f, t):
    out = []
    for y in range(max(f.year, 2015), t.year + 1):
        af, qf = date(y - 4, 1, 1), date(y - 2, 1, 1)
        out += [(y, '11011', af, date(y, 12, 31), date(y + 1, 3, 31)),
                (y, '11013', qf, date(y, 3, 31), date(y, 5, 15)),
                (y, '11012', qf, date(y, 6, 30), date(y, 8, 14)),
                (y, '11014', qf, date(y, 9, 30), date(y, 11, 14))]
    return out
def covering(f, t, today):
    av = [r for r in cands(f, t) if today >= r[4] and not (r[3] < f) and not (r[2] > t)]
    chosen, pos = [], f
    while pos <= t:
        reach = [r for r in av if not (r[3] < pos)]
        if not reach: break
        start = [r for r in reach if not (r[2] > pos)]
        if not start:
            pos = min(r[2] for r in reach); continue
        best = max(start, key=lambda r: r[3]); chosen.append(best); av.remove(best); pos = best[3] + timedelta(days=1)
    return chosen
for f in [date(2020, 1, 2), date(2021, 1, 4), date(2019, 9, 3)]:
    sizes = {}
    d = date(2026, 1, 1)
    while d <= date(2027, 12, 31):
        n = len(covering(f, d, d)); sizes.setdefault(n, []).append(d); d += timedelta(days=1)
    print(f'from={f}:', {k: (len(v), str(v[0]), str(v[-1])) for k, v in sorted(sizes.items())})
