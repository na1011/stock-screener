"""반기 v2 실측(0콜) — 6-K 누계 공시가 같은 공시 안에서 TTM을 지을 재료를 싣는가.

  [FACTCACHE=<dir>] [TAXONOMY=ifrs-full] python3 -I samefiling.py

누계 = 6-K 자기 기말에 끝나는 6개월(175~190일) · 9개월(265~280일) 기간. 그 공시 안에서
 (a) 작년 같은 길이 누계(비교열) (b) 작년 FY(350~380일 · 끝 = 올해 누계 시작 −1일) (c) 작년 꼬리(끝 = 같은 FY 끝 · 길이 12−k개월)
가 있는지 센다. TTM = 누계 + (b − a) 또는 누계 + (c).
"""
import glob, json, os, sys
from collections import Counter, defaultdict
from datetime import date, timedelta

CACHE = os.environ.get('FACTCACHE', '/Users/na1011/dev/stock-screener/.factcache')
TAX = os.environ.get('TAXONOMY', 'us-gaap')
TAG = ['ProfitLoss', 'ProfitLossAttributableToOwnersOfParent'] if TAX == 'ifrs-full' else ['NetIncomeLoss', 'ProfitLoss']


def d(s):
    return date.fromisoformat(s)


def length(f):
    return (d(f['end']) - d(f['start'])).days


out = Counter()
detail = defaultdict(Counter)
for path in sorted(glob.glob(os.path.join(CACHE, '*.json'))):
    t = os.path.basename(path)[:-5]
    g = json.load(open(path)).get('facts', {}).get(TAX, {})
    tag = next((x for x in TAG if x in g), None)
    if not tag:
        continue
    by = defaultdict(list)
    for arr in g[tag]['units'].values():
        for f in arr:
            if 'start' in f and f['form'].startswith('6-K'):
                by[f['accn']].append(f)
    for accn, fs in by.items():
        own = max(f['end'] for f in fs)
        if own < '2023-01-01':
            continue
        for f in fs:
            if f['end'] != own:
                continue
            n = length(f)
            k = 6 if 175 <= n <= 190 else 9 if 265 <= n <= 280 else None
            if not k:
                continue
            fy_end_prev = d(f['start']) - timedelta(days=1)
            a = any(abs((d(x['start']) - (d(f['start']) - timedelta(days=365))).days) <= 10
                    and abs(length(x) - n) <= 10 for x in fs)
            b = any(abs((d(x['end']) - fy_end_prev).days) <= 4 and 350 <= length(x) <= 380 for x in fs)
            tail = 182 if k == 6 else 91
            c = any(abs((d(x['end']) - fy_end_prev).days) <= 4 and abs(length(x) - tail) <= 12 for x in fs)
            key = f'{k}M ' + ('c' if c else '') + ('a+b' if a and b else 'a' if a else 'b' if b else '') or 'none'
            out[key] += 1
            detail[t][key] += 1
print(TAX, dict(sorted(out.items())))
for t, c in sorted(detail.items()):
    print(' ', t, dict(c))
