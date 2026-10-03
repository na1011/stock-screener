"""바뀐 Q4 지배주주 칸의 입력 출처 판정 — 자기 식이 아니라 원장 직접값과의 일치로."""
import csv, json, collections, sys, datetime as dt
S, base_p, var_p = sys.argv[1], sys.argv[2], sys.argv[3]
mem = dict(l.strip().split('|') for l in open(f'{S}/members.txt'))
def load(p):
    return {(r['ticker'], r['fy'], r['fp'], r['pub']): r for r in csv.DictReader(open(p), delimiter='\t') if r['ticker'] in mem}
B, V = load(base_p), load(var_p)
lab = collections.defaultdict(list)
for k, r in V.items(): lab[k[:3]].append(r)
for v in lab.values(): v.sort(key=lambda r: r['pub'])
num = lambda x: float(x) if x else None
d = dt.date.fromisoformat
cache = {}
def ledger(t):
    if t not in cache:
        cache[t] = json.load(open(f'.factcache/{t}.json'))['facts'].get('us-gaap', {})
    return cache[t]
def direct(t, start, end, pub):
    xs = [x for x in ledger(t).get('NetIncomeLoss', {}).get('units', {}).get('USD', [])
          if 'start' in x and abs((d(x['start']) - d(start)).days) <= 7 and abs((d(x['end']) - d(end)).days) <= 7 and x['filed'] <= pub]
    xs.sort(key=lambda x: x['filed'])
    return xs[-1]['val'] if xs else None
def at(v, day):
    b = None
    for r in v:
        if r['pub'] <= day: b = r
    return b
close = lambda a, b: a is not None and b is not None and abs(a - b) <= max(0.01 * max(abs(a), abs(b)), 1e6)
cls = collections.Counter(); st = collections.defaultdict(set); ex = collections.defaultdict(list)
for k in B.keys() & V.keys():
    if k[2] != 'Q4' or B[k]['netIncomeControlling'] == V[k]['netIncomeControlling']: continue
    t, fy, _, pub = k
    rows = [at(lab.get((t, fy, q), []), pub) for q in ('FY', 'Q1', 'Q2', 'Q3')]
    if any(r is None for r in rows):
        key = '입력 판 없음'
    else:
        ok = [close(num(r['netIncomeControlling']), direct(t, r['start'], r['end'], pub)) for r in rows]
        key = '입력 넷 다 원장 직접값' if all(ok) else f'폴백 입력 {4 - sum(ok)}'
    b, v = num(B[k]['netIncomeControlling']), num(V[k]['netIncomeControlling'])
    flip = b is not None and v is not None and (b > 0) != (v > 0)
    kk = (key, 'null→값' if b is None else ('부호반전' if flip else '값→값'))
    cls[kk] += 1; st[kk].add(t)
    if len(ex[kk]) < 4: ex[kk].append((t, fy, pub, b, v))
for kk in sorted(cls, key=lambda x: -cls[x]):
    print(f'{cls[kk]:5} {len(st[kk]):4}종목 {kk} {ex[kk][:3]}')
