"""부분 원가 줄 → 총이익 폴백: 최신 FY(FY ≥ 2022 · 최신 판)에서 저장 총이익이 없고 매출·원가가 있어 소비자가 매출 − 원가를 쓰는 행 중,
같은 공시(accn) · 같은 창에 저장 원가와 다른 원가 성분 태그가 0 아닌 값으로 함께 있는 행 — 판정 없는 검출(원가가 일부일 수 있다는 신호)."""
import csv, json, collections, sys, datetime as dt
PF = {'10-K', '10-K/A', '10-Q', '10-Q/A', '20-F', '20-F/A', '40-F', '40-F/A', '6-K', '6-K/A'}
S, tsv = sys.argv[1], sys.argv[2]
mem = dict(l.strip().split('|') for l in open(f'{S}/members.txt'))
d = dt.date.fromisoformat
FULL = ("CostOfGoodsAndServicesSold", "CostOfRevenue", "CostOfGoodsSold", "CostOfSales")
PART = ("CostOfServices", "CostOfGoodsAndServiceExcludingDepreciationDepletionAndAmortization",
        "CostOfServicesExcludingDepreciationDepletionAndAmortization", "CostOfGoodsSoldExcludingDepreciationDepletionAndAmortization",
        "CostOfRevenueExcludingDepreciationDepletionAndAmortization", "CostOfReimbursableExpense",
        "CostOfServicesDepreciationAndAmortization", "CostOfGoodsSoldDepreciationAndAmortization", "CostDepreciationAmortizationAndDepletion")
lab = collections.defaultdict(list)
for r in csv.DictReader(open(tsv), delimiter='\t'):
    if r['ticker'] in mem and r['fp'] == 'FY': lab[(r['ticker'], r['fy'])].append(r)
cache = {}
def g(t):
    if t not in cache: cache.clear(); cache[t] = json.load(open(f'.factcache/{t}.json'))['facts'].get('us-gaap', {})
    return cache[t]
def same(t, tag, accn, s, e):
    return [x['val'] for x in g(t).get(tag, {}).get('units', {}).get('USD', []) if x.get('accn') == accn and 'start' in x
            and abs((d(x['start']) - s).days) <= 7 and abs((d(x['end']) - e).days) <= 7]
c = collections.Counter(); st = collections.defaultdict(set); ex = collections.defaultdict(list)
for (t, fy), v in sorted(lab.items()):
    if int(fy) < 2022: continue
    r = max(v, key=lambda r: r['pub'])
    if r['grossProfit'] or not r['costOfSales'] or not r['revenue']: continue
    accn = r['rc'].split(':', 1)[1] if ':' in r['rc'] else r['rc']
    s, e, cost = d(r['start']), d(r['end']), float(r['costOfSales'])
    which = [tag for tag in FULL if any(abs(x - cost) < 1 for x in same(t, tag, accn, s, e))]
    other = {tag: same(t, tag, accn, s, e) for tag in PART}
    other = {k: v[0] for k, v in other.items() if v and v[0] != 0}
    k = ('성분 태그 함께 있음' if other else '성분 태그 없음', which[0] if which else '저장 원가 출처 불명(다른 공시)', mem[t])
    c[k] += 1; st[k].add(t)
    if other and len(ex[k]) < 10:
        rev = float(r['revenue'])
        ex[k].append(f"{t} {fy} 매출 {rev/1e6:,.0f} 원가 {cost/1e6:,.0f} GP율 {(rev-cost)/rev:.1%} 성분 {{{', '.join(f'{a[:22]}={b/1e6:,.0f}' for a, b in other.items())}}}")
for k in sorted(c): print(c[k], len(st[k]), k); [print('    ', x) for x in ex[k]]
