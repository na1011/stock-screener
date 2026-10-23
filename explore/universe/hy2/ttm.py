"""반기 v2 실측(0콜) — 누계 점의 TTM을 Q4 규칙(ADR-0151)으로 짓고 지금(FY 기저)과 견준다.

  [FACTCACHE=<dir>] [TAXONOMY=ifrs-full] [ASOF=2026-10-23] python3 -I ttm.py [티커 …]

판 = 같은 (시작, 끝)의 값이 공시마다 바뀐 이력(비교열 포함). 보이는 판 = ASOF 이하 가장 늦은 공시.
꼬리 = FY_작년(보이는 판) − 누계_작년(그 FY 판 공시일 이하에서 가장 늦은 판). 누계_작년이 「자기 공시」로 한 번도 안 났으면 미정.
"""
import glob, json, os, sys
from collections import defaultdict
from datetime import date, timedelta

CACHE = os.environ.get('FACTCACHE', '/Users/na1011/dev/stock-screener/.factcache')
TAX = os.environ.get('TAXONOMY', 'us-gaap')
ASOF = os.environ.get('ASOF', '2026-10-23')
NI = ['ProfitLoss', 'ProfitLossAttributableToOwnersOfParent'] if TAX == 'ifrs-full' else ['NetIncomeLoss', 'ProfitLoss']
REV = ['Revenue'] if TAX == 'ifrs-full' else ['Revenues', 'RevenueFromContractWithCustomerExcludingAssessedTax']


def d(s):
    return date.fromisoformat(s)


def n(s, e):
    return (d(e) - d(s)).days


def series(g, tags):
    tag = next((x for x in tags if x in g), None)
    if not tag:
        return {}, {}
    hist = defaultdict(list)  # (s,e) → [(filed, val)] 값이 바뀔 때만
    own = defaultdict(str)
    facts = [f for arr in g[tag]['units'].values() for f in arr if 'start' in f and f['filed'] <= ASOF]
    for f in facts:
        own[f['accn']] = max(own[f['accn']], f['end'])
    own_periods = {}
    for f in sorted(facts, key=lambda f: f['filed']):
        k = (f['start'], f['end'])
        h = hist[k]
        if not h or h[-1][1] != f['val']:
            h.append((f['filed'], f['val']))
        if f['end'] == own[f['accn']]:
            own_periods.setdefault(k, (f['filed'], f['form']))
    return hist, own_periods


def visible(h, at):
    v = [x for x in h if x[0] <= at]
    return v[-1] if v else None


def points(g, tags):
    hist, own = series(g, tags)
    fys = {k: v for k, v in own.items() if 350 <= n(*k) <= 380}
    out = []
    for k, (filed, form) in own.items():
        if not form.startswith('6-K') or not (175 <= n(*k) <= 190 or 265 <= n(*k) <= 280):
            continue
        fy_end = d(k[0]) - timedelta(days=1)
        fyk = next((x for x in fys if abs((d(x[1]) - fy_end).days) <= 4), None)
        prevk = next((x for x in own if abs((d(x[0]) - (d(k[0]) - timedelta(days=365))).days) <= 10
                      and abs((d(x[1]) - (d(k[1]) - timedelta(days=365))).days) <= 10), None)
        cur = visible(hist[k], ASOF)
        if fyk is None or prevk is None:
            out.append((k[1], n(*k), cur[1], None, 'FY없음' if fyk is None else '작년누계 원공시 없음'))
            continue
        fy = visible(hist[fyk], ASOF)
        prev = visible(hist[prevk], fy[0])
        if prev is None:
            out.append((k[1], n(*k), cur[1], None, '작년누계가 FY 판 뒤'))
            continue
        out.append((k[1], n(*k), cur[1], cur[1] + fy[1] - prev[1], f'FY{fyk[1][:4]}@{fy[0]} 누계작년@{prev[0]}'))
    latest_fy = max(((k[1], visible(hist[k], ASOF)[1]) for k in fys), default=None)
    return sorted(out), latest_fy


tickers = sys.argv[1:] or sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(CACHE, '*.json')))
for t in tickers:
    g = json.load(open(os.path.join(CACHE, t + '.json')))['facts'].get(TAX, {})
    for name, tags in (('NI', NI), ('REV', REV)):
        pts, fy = points(g, tags)
        if not pts:
            continue
        last = pts[-1]
        print(t, name, '지금 FY', fy, '| 누계 최신', last[0], f'{last[1]}일', 'TTM', last[3], last[4])
