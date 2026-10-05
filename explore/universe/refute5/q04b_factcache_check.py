# q04의 80종목이 SEC companyfacts 캐시(.factcache · 09-15 · 전역 최대 filed 2026-09-15)에 6월 이후 기말의 10-Q/10-K 팩트를 갖는가 — 외부 호출 0.
import json, os, sys
cache = '/Users/na1011/dev/stock-screener/.factcache'
ts = [l.strip() for l in open(os.path.join(os.path.dirname(__file__), 'q04_tickers.txt')) if l.strip()]
hits = {}
for t in ts:
    d = json.load(open(os.path.join(cache, t + '.json')))
    for tax, cs in d.get('facts', {}).items():
        for k, v in cs.items():
            for u, arr in v.get('units', {}).items():
                for x in arr:
                    if x.get('form') in ('10-Q', '10-K') and x.get('end', '') >= '2026-06-01':
                        hits.setdefault(t, set()).add((x.get('form'), x.get('fy'), x.get('fp'), x.get('end'), x.get('filed')))
print('tickers', len(ts), 'with_jun_end_fact', len(hits))
for t, s in hits.items(): print(t, sorted(s))
