"""반기 v2 실측(0콜) — 중간 공시가 자기 기말에 싣는 손익 기간 꼴을 발행사 · 회계연도마다 센다.

  [FACTCACHE=<dir>] [TAXONOMY=ifrs-full] python3 -I shapes.py <members.txt>

자기 기말 = 그 공시(accn)의 손익 팩트 중 가장 늦은 종료일. 중간 공시 폼(10-Q · 6-K와 정정)만 본다.
꼴 = 자기 기말에 끝나는 기간 길이의 집합(3 = 80~112일 · 6 = 175~190 · 9 = 265~280 · 12 = 350~380).
"""
import glob, json, os, sys
from collections import Counter, defaultdict
from datetime import date

CACHE = os.environ.get('FACTCACHE', '/Users/na1011/dev/stock-screener/.factcache')
members = set(l.strip() for l in open(sys.argv[1]) if l.strip())
TAX = os.environ.get('TAXONOMY', 'us-gaap')
TAGS = (['ProfitLoss', 'ProfitLossAttributableToOwnersOfParent', 'Revenue'] if TAX == 'ifrs-full'
        else ['NetIncomeLoss', 'ProfitLoss', 'Revenues', 'RevenueFromContractWithCustomerExcludingAssessedTax'])
INTERIM = ('10-Q', '10-Q/A', '6-K', '6-K/A')


def bucket(d):
    if 80 <= d <= 112: return 3
    if 175 <= d <= 190: return 6
    if 265 <= d <= 280: return 9
    if 350 <= d <= 380: return 12
    return None


rows = []
per = {}
for path in sorted(glob.glob(os.path.join(CACHE, '*.json'))):
    t = os.path.basename(path)[:-5]
    g = json.load(open(path)).get('facts', {}).get(TAX, {})
    by_accn = defaultdict(list)
    for tag in TAGS:
        for arr in (g.get(tag) or {}).get('units', {}).values():
            for f in arr:
                if 'start' in f:
                    by_accn[f['accn']].append(f)
    shapes = Counter()
    latest_end = None
    for accn, fs in by_accn.items():
        form = fs[0]['form']
        if form not in INTERIM:
            continue
        own = max(f['end'] for f in fs)
        if own < '2023-01-01':
            continue
        lens = sorted({b for f in fs if f['end'] == own
                       for b in [bucket((date.fromisoformat(f['end']) - date.fromisoformat(f['start'])).days)] if b})
        key = (form, tuple(lens))
        shapes[key] += 1
    if not shapes:
        continue
    forms = {k[0] for k in shapes}
    has3 = sum(n for k, n in shapes.items() if 3 in k[1])
    no3 = sum(n for k, n in shapes.items() if 3 not in k[1])
    per[t] = (shapes, has3, no3)

def kind(shapes):
    s = set(k[1] for k in shapes)
    if all(3 in x for x in s):
        return 'all3'
    if any(3 in x for x in s):
        return 'mixed'
    return 'no3'

c = Counter()
for t, (shapes, has3, no3) in per.items():
    c[(t in members, kind(shapes))] += 1
print('발행사(2023 뒤 중간 공시 있음)', len(per), dict(c))
for t, (shapes, has3, no3) in sorted(per.items()):
    k = kind(shapes)
    forms = Counter(f for f, _ in shapes.elements())
    if k != 'all3' or any(not f.startswith('10-Q') for f in forms):
        print(t, 'M' if t in members else '-', k, ' '.join(f'{f}:{"+".join(map(str,l)) or "none"}x{n}' for (f, l), n in sorted(shapes.items())))
