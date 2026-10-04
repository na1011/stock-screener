"""P2a 실측 — K11(같은 공시 안 다른 창과 같은 값 + 앞 게재값과 1% 넘게 다름) 사건의 뒷이야기로 정밀도를 잰다(원장 · 0콜).
뒤 공시(다른 accn)가 그 창을 다시 실었을 때: 앞 값으로 돌아감(오태깅 증거) / 사건 값을 확인(정당한 재작성 증거) / 제3의 값 / 뒤 공시 없음.
쌍둥이 관계(사건 창 종류 → 쌍둥이 창 종류 · 같은 해/전년)도 센다."""
import collections, csv, datetime as dt, importlib.util, sys
spec = importlib.util.spec_from_file_location('cat', sys.argv[1]); cat = importlib.util.module_from_spec(spec); spec.loader.exec_module(cat)
ev = [l.rstrip('\n').split('\t') for l in open(sys.argv[2])]
d = dt.date.fromisoformat
res = collections.Counter(); rel_c = collections.Counter(); ex = collections.defaultdict(list)
cache = {}
for t, grp, col, s, e, k, filed, accn, det, tw in ev:
    if t not in cache:
        cache.clear(); cache[t] = cat.load(f'/Users/na1011/dev/stock-screener/.factcache/{t}.json')
    rows = cache[t].get((col, d(s), d(e)), [])
    prev, val = [float(x) for x in det.split('>')]
    later = [r for r in rows if r[0] > d(filed) and r[1] != accn]
    later_forms = [r[2] for r in later]
    if not later: fate = '뒤 공시 없음'
    elif any(cat.rel(r[4], prev) <= 0.01 for r in later) and not any(cat.rel(r[4], val) <= 0.01 for r in later): fate = '앞 값으로 돌아감'
    elif any(cat.rel(r[4], val) <= 0.01 for r in later): fate = '사건 값 확인(' + ('10-K' if any(f.startswith('10-K') for f, r in zip(later_forms, later) if cat.rel(r[4], val) <= 0.01) else '10-Q') + ')'
    else: fate = '제3의 값'
    ts, te = tw.replace('twin=', '').split('~')
    tk = cat.kind_of(d(ts), d(te))
    rel = f'{k}→{tk}' + (' 같은 끝' if te == e else (' 전년' if abs((d(te) - d(e)).days - 365) < 15 or abs((d(e) - d(te)).days - 365) < 15 else ''))
    res[(grp, fate)] += 1; rel_c[(rel, fate)] += 1
    ex[fate].append((t, col, s, e, filed, det, tw))
for k, v in sorted(res.items()): print(v, k)
print('-- 관계 × 뒷이야기')
for k, v in sorted(rel_c.items(), key=lambda kv: -kv[1])[:25]: print(v, k)
for f in ex: print(f, ex[f][:6])
