"""반기 v2 실측(0콜) — 누계 TTM 짝짓기 규칙이 몇 점에서 서는가.

  [FACTCACHE=<dir>] [TAXONOMY=ifrs-full] python3 -I pairing.py

점 = 6-K 자기 기말의 6 · 9개월 누계(올해). TTM = 누계_올해 + FY_작년 − 누계_작년.
규칙(판 이력): 누계_작년의 「원 공시」(자기 기말이 그 기간인 6-K)가 있고, 그 값이 올해 공시 비교열과 같으며(재작성 없음),
원 공시일 ≤ FY_작년 공시일(연차 폼의 자기 기간 · 가장 이른 것). 셋 중 하나라도 어긋나면 그 점은 「미정」.
태그 하나(순이익)로 센다 — 행 단위 판정은 아니다(다른 칸의 재작성은 안 본다).
"""
import glob, json, os
from collections import Counter, defaultdict
from datetime import date, timedelta

CACHE = os.environ.get('FACTCACHE', '/Users/na1011/dev/stock-screener/.factcache')
TAX = os.environ.get('TAXONOMY', 'us-gaap')
TAGS = ['ProfitLoss', 'ProfitLossAttributableToOwnersOfParent'] if TAX == 'ifrs-full' else ['NetIncomeLoss', 'ProfitLoss']
ANNUAL = ('10-K', '20-F', '40-F', '10-KT')


def d(s):
    return date.fromisoformat(s)


def n(f):
    return (d(f['end']) - d(f['start'])).days


res = Counter()
why = Counter()
cases = []
for path in sorted(glob.glob(os.path.join(CACHE, '*.json'))):
    t = os.path.basename(path)[:-5]
    g = json.load(open(path)).get('facts', {}).get(TAX, {})
    tag = next((x for x in TAGS if x in g), None)
    if not tag:
        continue
    facts = [f for arr in g[tag]['units'].values() for f in arr if 'start' in f]
    own_end = defaultdict(str)
    for f in facts:
        own_end[f['accn']] = max(own_end[f['accn']], f['end'])
    # 연차 폼 자기 기간 FY: 끝 → 가장 이른 공시일
    fy_pub = {}
    for f in facts:
        if f['form'].split('/')[0] in ANNUAL and f['end'] == own_end[f['accn']] and 350 <= n(f) <= 380:
            if f['end'] not in fy_pub or f['filed'] < fy_pub[f['end']]:
                fy_pub[f['end']] = f['filed']
    own_ytd = {}  # (start,end) → (filed, val) 원 공시
    for f in facts:
        if f['form'].startswith('6-K') and f['end'] == own_end[f['accn']] and (175 <= n(f) <= 190 or 265 <= n(f) <= 280):
            k = (f['start'], f['end'])
            if k not in own_ytd or f['filed'] < own_ytd[k][0]:
                own_ytd[k] = (f['filed'], f['val'], f['accn'])
    for (s, e), (filed, val, accn) in own_ytd.items():
        if e < '2023-01-01':
            continue
        k = 6 if n({'start': s, 'end': e}) < 200 else 9
        fy_end = d(s) - timedelta(days=1)
        fy = next(((x, p) for x, p in fy_pub.items() if abs((d(x) - fy_end).days) <= 4), None)
        prev = next(((ks, v) for ks, v in own_ytd.items()
                     if abs((d(ks[0]) - (d(s) - timedelta(days=365))).days) <= 10
                     and abs((d(ks[1]) - (d(e) - timedelta(days=365))).days) <= 10), None)
        cmp_ = [f for f in facts if f['accn'] == accn
                and abs((d(f['start']) - (d(s) - timedelta(days=365))).days) <= 10
                and abs((d(f['end']) - (d(e) - timedelta(days=365))).days) <= 10]
        if fy is None:
            r = 'FY작년 없음'
        elif prev is None:
            r = '작년 누계 원공시 없음(비교열만)' if cmp_ else '작년 누계 없음'
        elif prev[1][0] > fy[1]:
            r = '작년 누계 원공시가 FY 뒤'
        elif cmp_ and cmp_[0]['val'] != prev[1][1]:
            r = '재작성(비교열≠원공시)'
            cases.append((t, k, s, e, prev[1][1], cmp_[0]['val'], fy[1], filed))
        else:
            r = '섬'
        res[f'{k}M'] += 1
        why[(f'{k}M', r)] += 1
print(TAX, dict(res))
for k, v in sorted(why.items()):
    print(' ', k, v)
for c in cases:
    print('  재작성', c)
