"""P2b 실측 — K4(공시마다 고른 태그가 바뀌며 값이 1% 넘게 바뀜) 사건에 「같은 개념 가드」를 걸면 어떻게 갈리나(원장 · 0콜).
가드: 바뀐 공시 f2가 새 태그 t2로 실은 다른 창 W2 중, 옛 태그 t1이 (f2 또는 그 이전 공시에서) 실린 창이 있으면 두 값을 견준다 —
  같음(1%) = 같은 개념(받는다) · 다름 = 다른 줄(버린다) · 공유 창 없음 = 판정 불가. 뒷이야기: 뒤 공시가 W를 v2로(확인) / v1로(돌아감)."""
import collections, datetime as dt, importlib.util, json, sys
spec = importlib.util.spec_from_file_location('cat', sys.argv[1]); cat = importlib.util.module_from_spec(spec); spec.loader.exec_module(cat)
ev = [l.rstrip('\n').split('\t') for l in open(sys.argv[2])]
d = dt.date.fromisoformat
res = collections.Counter(); ex = collections.defaultdict(list); cache = {}
for t, grp, col, s, e, k, filed, accn, det, r in ev:
    if col in cat.INSTANT and len(sys.argv) < 4: pass
    if t not in cache:
        cache.clear(); cache[t] = json.load(open(f'/Users/na1011/dev/stock-screener/.factcache/{t}.json'))['facts'].get('us-gaap', {})
    g = cache[t]
    left, right = det.split('>')
    t1, v1 = left.rsplit(':', 1); t2, v2 = right.rsplit(':', 1); v1 = float(v1); v2 = float(v2)
    def facts(tag):
        return [x for x in g.get(tag, {}).get('units', {}).get('USD', []) if x.get('form') in cat.ANNUAL_FORMS | {'10-Q', '10-Q/A', '6-K', '6-K/A'}]
    f1s, f2s = facts(t1), facts(t2)
    key = lambda x: (x.get('start'), x['end'])
    mine2 = {key(x): x['val'] for x in f2s if x['accn'] == accn}
    verdicts = []
    for w, val2 in mine2.items():
        if w == ((s or None), e) or (w[0] == s and w[1] == e): continue
        prior1 = [x for x in f1s if key(x) == w and x['filed'] <= filed]
        if prior1:
            v = max(prior1, key=lambda x: x['filed'])['val']
            verdicts.append(cat.rel(v, val2) <= 0.01)
    guard = '공유 창 없음' if not verdicts else ('같은 개념' if all(verdicts) else ('다른 줄' if not any(verdicts) else '섞임'))
    later = [x for tag in (t1, t2) for x in facts(tag) if key(x) == ((s or None), e) and x['filed'] > filed and x['accn'] != accn]
    fate = '뒤 없음' if not later else ('확인' if any(cat.rel(x['val'], v2) <= 0.01 for x in later) and not any(cat.rel(x['val'], v1) <= 0.01 for x in later) else ('돌아감' if any(cat.rel(x['val'], v1) <= 0.01 for x in later) else '제3'))
    kind = 'I' if col in cat.INSTANT else k
    res[(grp, kind, guard, fate)] += 1; ex[(guard, fate)].append((t, col, s, e, filed, t1, v1, t2, v2))
tot = collections.Counter()
for (grp, kind, guard, fate), n in res.items():
    if grp == 'current': tot[(guard, fate)] += n
for kk, n in sorted(tot.items()): print(n, kk)
print('-- 유량만(현 편입)')
fl = collections.Counter()
for (grp, kind, guard, fate), n in res.items():
    if grp == 'current' and kind != 'I': fl[(guard, fate)] += n
for kk, n in sorted(fl.items()): print(n, kk)
for kk in [('같은 개념', '확인'), ('같은 개념', '돌아감'), ('다른 줄', '확인'), ('다른 줄', '돌아감')]: print(kk, ex[kk][:5])
