"""남은 TTM 결함의 분기별 귀속 — 그날 보이는 네 분기의 저장 지배주주를 원장 누계 차분(그날 이하 최신)과 대조.
분기 k의 원장값 = 지배(누계 k) − 지배(누계 k−1) · Q4 = 지배(FY) − 지배(9개월) · Q1 = 지배(3개월)."""
import sys, collections
S, tsv, spec = sys.argv[1], sys.argv[2], sys.argv[3]
sys.argv = ['x', S, f'{S}/{tsv}']
exec(open(f'{S}/ttm.py').read().split('res = [collections.Counter()')[0])
def allvals(t, tag, s, e, day):
    f, v = series(t, tag, s, e)
    return [v[i] for i in range(len(f)) if f[i] <= day]
for item in spec.split(','):
    t, day = item.split('@')
    led(t)
    lab = data[0].get(t, {})
    qs = []
    for (fy, fp), v in lab.items():
        if fp in ('Q1', 'Q2', 'Q3', 'Q4'):
            r = visible(v, day)
            if r: qs.append((r['end'], fy, fp, r))
    qs.sort(reverse=True); qs = qs[:4]
    out = []
    for _, fy, fp, r in qs:
        y = int(fy)
        def w(p):
            vv = lab.get((str(y), p)); return (d(vv[-1]['start']), d(vv[-1]['end'])) if vv else None
        q1 = w('Q1'); cur = (d(r['start']), d(r['end']))
        if fp == 'Q1':
            led_v = ctrl(t, *cur, day)
        elif fp == 'Q4':
            fyw, q3 = w('FY'), w('Q3')
            a = ctrl(t, *fyw, day) if fyw else None; b = ctrl(t, q1[0], q3[1], day) if q1 and q3 else None
            led_v = a - b if a is not None and b is not None else None
        else:
            prev = w('Q' + str(int(fp[1]) - 1))
            a = ctrl(t, q1[0], cur[1], day) if q1 else None
            b = ctrl(t, q1[0], prev[1], day) if q1 and prev else None
            led_v = a - b if a is not None and b is not None else None
        st = num(r['netIncomeControlling']); ni = num(r['netIncome'])
        three = allvals(t, 'NetIncomeLoss', *cur, day)
        pl3 = allvals(t, 'ProfitLoss', *cur, day)
        if st is None: k = 'null'
        elif led_v is None: k = '원장 차분 없음'
        elif abs(st - led_v) <= max(0.01 * max(abs(st), abs(led_v)), 5e5): k = 'ok'
        elif abs(st + led_v) <= max(0.01 * abs(led_v), 5e5): k = '부호'
        elif ni is not None and st == ni and pl3 and abs(st - pl3[-1]) < 5e5: k = '연결 폴백'
        elif any(abs(st - x) < 5e5 for x in three[:-1]): k = '옛 판(3개월 NIL 앞 공시값)'
        elif r['derived'] == 'true': k = 'Q4 잔차 어긋남'
        else: k = '기타'
        out.append(f"{fy}{fp}:{k}({'' if st is None else round(st / 1e6, 1)} vs {'' if led_v is None else round(led_v / 1e6, 1)})")
    print(t, day, ' | '.join(out))
