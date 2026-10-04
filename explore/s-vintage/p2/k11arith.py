"""P2a — K11 후보를 「그 공시 안(과 그 시점 이전 공시)의 산수」로 판정할 수 있나. 시점에 쓸 수 있는 증거만 쓴다.
창 W(값 v · 앞 값 p)에 대해 같은 공시 f가 실은 누계로 W를 함의할 수 있으면 함의값 m:
  - W가 분기(Q): f에 같은 끝의 누계 C(시작 = 회계연도 시작)와 W 바로 앞까지의 누계 C'(f 또는 f 이전 공시의 마지막 값)가 있으면 m = C − C'
    (W가 1분기면 C 자체가 3개월이라 판정 못 함)
  - W가 FY: f에 그 해 분기 셋 + Q4(또는 9개월 누계 + Q4)가 있으면 m = 합
판정: |v − m| ≤ 1% → v 지지(정당) · |p − m| ≤ 1% → p 지지(v는 오태깅) · 둘 다 아님 → 판정 불가.
뒷이야기(k11fate와 같은 정의)와 교차표로 낸다."""
import collections, datetime as dt, importlib.util, sys
spec = importlib.util.spec_from_file_location('cat', sys.argv[1]); cat = importlib.util.module_from_spec(spec); spec.loader.exec_module(cat)
ev = [l.rstrip('\n').split('\t') for l in open(sys.argv[2])]
d = dt.date.fromisoformat
def fate(rows, filed, accn, p, v):
    later = [r for r in rows if r[0] > d(filed) and r[1] != accn]
    if not later: return '뒤 없음'
    if any(cat.rel(r[4], p) <= 0.01 for r in later) and not any(cat.rel(r[4], v) <= 0.01 for r in later): return '돌아감'
    if any(cat.rel(r[4], v) <= 0.01 for r in later): return '확인'
    return '제3'
def at_or_before(rows, accn, filed):
    """f 자신의 값 우선, 없으면 f 이전 공시의 마지막 값."""
    own = [r for r in rows if r[1] == accn]
    if own: return own[-1][4]
    prior = [r for r in rows if r[0] < d(filed)]
    return prior[-1][4] if prior else None
res = collections.Counter(); ex = collections.defaultdict(list); cache = {}
for t, grp, col, s, e, k, filed, accn, det, tw in ev:
    if t not in cache:
        cache.clear(); cache[t] = cat.load(f'/Users/na1011/dev/stock-screener/.factcache/{t}.json')
    data = cache[t]; S, E = d(s), d(e)
    p, v = [float(x) for x in det.split('>')]
    rows = data.get((col, S, E), [])
    m = None
    if k == 'Q':
        # 회계연도 시작: 같은 끝 누계 중 가장 긴 것(≤ 1년)의 시작
        cums = [(cs, ce) for (c, cs, ce) in data if c == col and ce == E and cs is not None and cs < S and (E - cs).days < 300 and any(r[1] == accn for r in data[(c, cs, ce)])]
        if cums:
            ys = min(cs for cs, ce in cums)
            C = [r for r in data[(col, ys, E)] if r[1] == accn][-1][4]
            prev_end = S - dt.timedelta(days=1)
            cands = [(c, cs, ce) for (c, cs, ce) in data if c == col and cs == ys and abs((ce - prev_end).days) <= 7]
            if cands:
                Cp = at_or_before(data[cands[0]], accn, filed)
                if Cp is not None: m = C - Cp
    elif k == 'F':
        qs = sorted([(cs, ce) for (c, cs, ce) in data if c == col and cs is not None and S - dt.timedelta(days=7) <= cs and ce <= E and cat.kind_of(cs, ce) == 'Q' and any(r[1] == accn for r in data[(c, cs, ce)])])
        if len(qs) == 4:
            m = sum([r for r in data[(col,) + q] if r[1] == accn][-1][4] for q in qs)
    if m is None: verdict = '판정 불가'
    elif cat.rel(v, m) <= 0.01 and cat.rel(p, m) > 0.01: verdict = 'v 지지'
    elif cat.rel(p, m) <= 0.01 and cat.rel(v, m) > 0.01: verdict = 'p 지지(오태깅)'
    else: verdict = '둘 다 아님'
    f = fate(rows, filed, accn, p, v)
    res[(verdict, f)] += 1; ex[verdict].append((t, col, s, e, filed, p, v, m))
for kk, n in sorted(res.items()): print(n, kk)
for vv in ex: print(vv, ex[vv][:8])
