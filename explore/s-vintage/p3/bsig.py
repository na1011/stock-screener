"""P3 실측 3 — B 신호(기준 변경이 FY 공시 뒤에 일어났다는 시점 증거)의 정밀도·재현율.
사건(pop.py와 같은 정의: FY 첫 판 뒤 Y 분기가 1% 넘게 바뀐 판으로 나옴)마다 첫 재발행 공시(accn)를 보고:
 B1 = 그 공시가 FY(Y) 기말 잔액(Assets·Liabilities·StockholdersEquity·…IncludingNCI·LiabilitiesAndStockholdersEquity) 중
      하나라도 FY 첫 판 공시(10-K)와 0.1% 넘게 다르게 실었다(원장 · 같은 기말 instant).
 B2 = 어댑터 출력에 그 공시일에 FY(Y) 새 판이 있다(잔액이든 손익이든 FY 창 사실을 다시 실었다).
사후: FY(Y)가 첫 재발행 뒤 같은 컬럼에서 바뀌었나(B?) / 안 바뀜(A?) / 혼합."""
import collections, csv, json, sys, os
exec(open(os.path.join(os.path.dirname(sys.argv[0]), 'pop.py')).read().split("ev = []")[0])
BAL = ['Assets', 'Liabilities', 'StockholdersEquity', 'StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest', 'LiabilitiesAndStockholdersEquity']
cache = {}
def inst(t, accn, date):
    if t not in cache:
        cache.clear()
        try: cache[t] = json.load(open(f'/Users/na1011/dev/stock-screener/.factcache/{t}.json'))['facts'].get('us-gaap', {})
        except FileNotFoundError: cache[t] = {}
    out = {}
    for tag in BAL:
        for x in cache[t].get(tag, {}).get('units', {}).get('USD', []):
            if 'start' not in x and x['end'] == date and x['accn'] == accn: out[tag] = x['val']
    return out
res = collections.Counter(); ex = collections.defaultdict(list)
for t, lab in by.items():
    if mem[t] != 'current': continue
    for (y, fp), fv in lab.items():
        if fp != 'FY' or y < 2015: continue
        fv = sorted(fv, key=lambda r: r['pub']); p0 = fv[0]['pub']; fend = fv[0]['end']
        qs = [lab.get((y, q)) for q in ('Q1', 'Q2', 'Q3')]
        if None in qs: continue
        for c, f in COLS.items():
            ref = []
            for qi, qv in enumerate(qs):
                b = at(qv, p0)
                if b is None: continue
                for r in sorted(qv, key=lambda r: r['pub']):
                    if r['pub'] > p0 and diff(f(b), f(r)): ref.append(r); break
            if not ref: continue
            first = min(ref, key=lambda r: r['pub'])
            fchg = [r['pub'] for r in fv[1:] if diff(f(r), f(fv[0]))]
            post = 'A?' if not fchg else ('B?' if min(fchg) > first['pub'] else '혼합')
            a10k = fv[0]['rc'].split(':', 1)[1]; aq = first['rc'].split(':', 1)[1]
            i0, i1 = inst(t, a10k, fend), inst(t, aq, fend)
            common = set(i0) & set(i1)
            b1 = None if not common else any(abs(i0[k] - i1[k]) > 0.001 * max(abs(i0[k]), abs(i1[k]), 1) for k in common)
            b2 = any(r['pub'] == first['pub'] for r in fv)
            res[(c, post, 'B1', b1)] += 1; res[(c, post, 'B2', b2)] += 1
            ex[(c, post, b1)].append((t, y))
for c in COLS:
    print('==', c)
    for sig in ('B1', 'B2'):
        tp = res[(c, 'B?', sig, True)]; fp_ = res[(c, 'A?', sig, True)]; mix = res[(c, '혼합', sig, True)]
        allB = sum(n for (cc, p, s, v), n in res.items() if cc == c and p == 'B?' and s == sig)
        none = sum(n for (cc, p, s, v), n in res.items() if cc == c and s == sig and v is None)
        print(f'  {sig}: 참→B? {tp} · 참→A? {fp_} · 참→혼합 {mix} · 정밀도(B?/(B?+A?)) {tp / max(1, tp + fp_):.0%} · B? 재현율 {tp / max(1, allB):.0%} · 판정불가 {none}')
    print('   B1 참·A? 예:', ex[(c, 'A?', True)][:10])
    print('   B1 거짓·B? 예:', ex[(c, 'B?', False)][:10])
