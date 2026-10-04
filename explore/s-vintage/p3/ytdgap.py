"""P3 실측 4 — 저장 분기(어댑터 3개월 행) 대 원장 누계 차분(같은 시점에 보이는 공시값)의 갈림.
분기 k(2·3)·Q4: 함의값(p) = Ck(p) − C(k-1)(p) (C1 = Q1 · Q4는 FY − C3) — 각 항은 p 이하 공시 중 그 컬럼을 실은 마지막 공시값(진실 표).
저장값(p) = 어댑터 출력에서 p에 보이는 그 라벨의 판. 1%·50만 넘게 다르면 「갈림」 — 사건 = (종목, Y, 분기, 컬럼), 처음 갈린 날과 그 뒤 해소 여부.
누계로만 드러난 재작성(BDX·BIIB)의 크기와 시점 정합(갈림이 언제 원장에 보였나)을 잰다."""
import bisect, collections, csv, sys
num = lambda x: float(x) if x not in ('', None) else None
COLS = {'rev': 'revenue', 'ni': 'netIncome', 'nic': 'netIncomeControlling', 'cost': 'costOfSales'}
def load(p):
    by = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in csv.DictReader(open(p), delimiter='\t'):
        by[r['ticker']][(int(r['fy']), r['fp'])].append(r)
    for t in by:
        for v in by[t].values(): v.sort(key=lambda r: r['pub'])
    return by
ad, tr = load(sys.argv[1]), load(sys.argv[2])
mem = dict(l.strip().split('|') for l in open(sys.argv[3]) if l.strip())
def stated(v, p, c):
    best = None
    for r in v or []:
        if r['pub'] > p: break
        x = num(r[c])
        if x is not None: best = x
    return best
def vis(v, p):
    b = None
    for r in v or []:
        if r['pub'] <= p: b = r
    return b
def wrong(a, b): return abs(a - b) > max(0.01 * max(abs(a), abs(b)), 5e5)
ev = collections.Counter(); per = collections.defaultdict(list)
for t in ad:
    if mem.get(t) != 'current': continue
    for (y, fp) in list(ad[t]):
        if fp != 'FY' or y < 2016: continue
        for q in ('Q2', 'Q3', 'Q4'):
            prev = {'Q2': 'Q1', 'Q3': 'C2', 'Q4': 'C3'}[q]; cur = {'Q2': 'C2', 'Q3': 'C3', 'Q4': 'FY'}[q]
            pubs = sorted({r['pub'] for lab in (prev, cur) for r in tr[t].get((y, lab), [])} | {r['pub'] for r in ad[t].get((y, q), [])})
            for k, c in COLS.items():
                first = None; last_state = None
                for p in pubs:
                    a, b = stated(tr[t].get((y, prev)), p, c), stated(tr[t].get((y, cur)), p, c)
                    s = vis(ad[t].get((y, q)), p)
                    if None in (a, b) or s is None or num(s[c]) is None: continue
                    g = wrong(b - a, num(s[c]))
                    if g and first is None: first = p
                    last_state = g
                if first is not None:
                    ev[(q, k, '끝까지 갈림' if last_state else '해소됨', '2020+' if y >= 2020 else '~2019')] += 1
                    per[(q, k)].append((t, y, first))
for key in sorted(ev): print(ev[key], key)
for q in ('Q2', 'Q3', 'Q4'):
    print(q, 'rev 예:', per[(q, 'rev')][:12])
