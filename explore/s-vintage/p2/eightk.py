"""P2c 실측 — 8-K(6-K 아님)가 실은 기간 손익 팩트(핵심 태그)의 모양과 운명. 원장 · 0콜.
공시(accn)마다: 창 종류(FY·분기·누계) · 그 창의 앞 정기 공시 값과 같음/다름 · 뒤 정기 공시가 8-K 값을 확인/다름/없음."""
import collections, datetime as dt, json, sys
TAGS = ['Revenues', 'RevenueFromContractWithCustomerExcludingAssessedTax', 'NetIncomeLoss', 'ProfitLoss', 'OperatingIncomeLoss', 'CostOfRevenue', 'CostOfGoodsAndServicesSold']
PER = {'10-K', '10-K/A', '10-Q', '10-Q/A', '20-F', '20-F/A', '40-F', '40-F/A', '6-K', '6-K/A'}
d = dt.date.fromisoformat
mem = dict(l.strip().split('|') for l in open(sys.argv[1]) if l.strip())
def kind(s, e):
    n = (d(e) - d(s)).days
    return 'Q' if 80 <= n <= 100 else 'H' if 170 <= n <= 190 else 'N' if 260 <= n <= 285 else 'F' if 350 <= n <= 380 else '?'
def rel(a, b):
    m = max(abs(a), abs(b)); return 0 if m == 0 else abs(a - b) / m
res = collections.Counter(); filings = collections.defaultdict(set); ex = collections.defaultdict(list); years = collections.Counter()
for t, m in mem.items():
    try: g = json.load(open(f'/Users/na1011/dev/stock-screener/.factcache/{t}.json'))['facts'].get('us-gaap', {})
    except FileNotFoundError: continue
    for tag in TAGS:
        xs = [x for x in g.get(tag, {}).get('units', {}).get('USD', []) if 'start' in x]
        for x in xs:
            if not x.get('form', '').startswith('8-K'): continue
            k = kind(x['start'], x['end'])
            same = [y for y in xs if y.get('form') in PER and abs((d(y['start']) - d(x['start'])).days) <= 7 and abs((d(y['end']) - d(x['end'])).days) <= 7]
            before = [y for y in same if y['filed'] < x['filed']]; after = [y for y in same if y['filed'] > x['filed']]
            b = '앞 정기 없음' if not before else ('앞과 같음' if rel(max(before, key=lambda y: y['filed'])['val'], x['val']) <= 0.01 else '앞과 다름')
            a = '뒤 정기 없음' if not after else ('뒤가 확인' if rel(min(after, key=lambda y: y['filed'])['val'], x['val']) <= 0.01 else '뒤가 다름')
            res[(m, k, b, a)] += 1; filings[(m, b)].add((t, x['accn']))
            years[x['filed'][:4]] += 1
            if b == '앞과 다름': ex[(k, a)].append((t, tag, x['start'], x['end'], x['filed'], x['val'] / 1e6))
for key in sorted(res, key=lambda k: -res[k])[:30]: print(res[key], key)
print('공시 수(편입 × 앞과의 관계):', {k: len(v) for k, v in filings.items()})
print('8-K 팩트 공시 연도:', sorted(years.items()))
for k in [('F', '뒤가 확인'), ('F', '뒤가 다름'), ('Q', '뒤가 확인'), ('Q', '뒤가 다름'), ('F', '뒤 정기 없음')]:
    print(k, len(ex[k]), ex[k][:6])
