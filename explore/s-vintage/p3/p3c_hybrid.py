"""P3c — 소스 Q4 「연결만」 지배주주 경로(S0 v5의 S_NIQ4SRC=2 + S_NITOTAL)를 「저장 ΣQ1~3 지배 ≈ 원장 9개월 누계 지배(그 공시일 이하)」일 때만 받는 하이브리드.
대조(ctl) 행을 기본으로, 변형이 바꾼 Q4 지배주주 칸 중 술어가 서는 것만 변형 값으로 갈아 끼운다(판 집합은 대조 것). 술어가 못 서면(원장 9개월 없음) 대조를 지킨다."""
import collections, csv, sys
ctl, var, truth, out = sys.argv[1:5]
num = lambda x: float(x) if x not in ('', None) else None
def load(p):
    by = collections.defaultdict(lambda: collections.defaultdict(list)); rows = []
    for r in csv.DictReader(open(p), delimiter='\t'):
        by[r['ticker']][(r['fy'], r['fp'])].append(r); rows.append(r)
    for t in by:
        for v in by[t].values(): v.sort(key=lambda r: r['pub'])
    return by, rows
cb, crows = load(ctl); vb, _ = load(var); tb, _ = load(truth)
def vis(v, p):
    b = None
    for r in v or []:
        if r['pub'] <= p: b = r
    return b
def stated(v, p, c):
    b = None
    for r in v or []:
        if r['pub'] > p: break
        if num(r[c]) is not None: b = num(r[c])
    return b
vq4 = {(r['ticker'], r['fy'], r['pub']): r for t in vb for (fy, fp), v in vb[t].items() if fp == 'Q4' for r in v}
stat = collections.Counter(); ex = collections.defaultdict(list)
for r in crows:
    if r['fp'] != 'Q4': continue
    k = (r['ticker'], r['fy'], r['pub']); v = vq4.get(k)
    if v is None or v['netIncomeControlling'] == r['netIncomeControlling']: continue
    qs = [vis(cb[r['ticker']].get((r['fy'], q)), r['pub']) for q in ('Q1', 'Q2', 'Q3')]
    sq = None if None in qs or any(num(q['netIncomeControlling']) is None for q in qs) else sum(num(q['netIncomeControlling']) for q in qs)
    l9 = stated(tb[r['ticker']].get((r['fy'], 'C3')), r['pub'], 'netIncomeControlling')
    if sq is None or l9 is None:
        stat['술어 못 섬(재료 없음) — 대조 유지'] += 1; ex['못 섬'].append(k); continue
    if abs(sq - l9) <= max(0.01 * max(abs(sq), abs(l9)), 5e5):
        r['netIncomeControlling'] = v['netIncomeControlling']; stat['술어 섬 — 변형 값'] += 1; ex['섬'].append(k)
    else:
        stat['ΣQ ≠ 원장 9개월 — 대조 유지'] += 1; ex['안 섬'].append((k, round(sq / 1e6, 1), round(l9 / 1e6, 1)))
w = csv.DictWriter(open(out, 'w'), fieldnames=list(crows[0].keys()), delimiter='\t', lineterminator='\n')
w.writeheader(); w.writerows(crows)
print(dict(stat)); print('안 섬 예:', ex['안 섬'][:12]); print('못 섬 예:', ex['못 섬'][:8])
