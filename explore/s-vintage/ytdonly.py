"""분기 행의 순이익(연결 컬럼)이 null인데 그 행 공시일 이하에 같은 해 누계(k)와 누계(k−1)가 원장에 있는 칸 — 누계 차분으로 열 수 있는 분기.
편입(현·과거) · 기간 끝 ≥ 2020-07-01 · 최신 판만. 태그: NetIncomeLoss 또는 ProfitLoss(같은 태그끼리)."""
import csv, json, collections, sys, datetime as dt
PF = {'10-K', '10-K/A', '10-Q', '10-Q/A', '20-F', '20-F/A', '40-F', '40-F/A', '6-K', '6-K/A'}
S, tsv = sys.argv[1], sys.argv[2]
mem = dict(l.strip().split('|') for l in open(f'{S}/members.txt'))
d = dt.date.fromisoformat
lab = collections.defaultdict(list)
for r in csv.DictReader(open(tsv), delimiter='\t'):
    if r['ticker'] in mem: lab[(r['ticker'], r['fy'], r['fp'])].append(r)
for v in lab.values(): v.sort(key=lambda r: r['pub'])
cache = {}
def g(t):
    if t not in cache: cache.clear(); cache[t] = json.load(open(f'.factcache/{t}.json'))['facts'].get('us-gaap', {})
    return cache[t]
def has(t, tag, s, e, pub):
    return [x['val'] for x in g(t).get(tag, {}).get('units', {}).get('USD', []) if x.get('form') in PF and 'start' in x
            and abs((d(x['start']) - s).days) <= 7 and abs((d(x['end']) - e).days) <= 7 and x['filed'] <= pub]
c = collections.Counter(); st = collections.defaultdict(set); ex = collections.defaultdict(list)
for (t, fy, fp), v in sorted(lab.items()):
    if fp not in ('Q2', 'Q3'): continue
    r = v[-1]
    if r['end'] < '2020-07-01' or r['netIncome'] != '': continue
    q1 = lab.get((t, fy, 'Q1')); prev = lab.get((t, fy, 'Q' + str(int(fp[1]) - 1)))
    if not q1 or not prev:
        k = '앞 분기 행 없음'
    else:
        s0, e1, e0 = d(q1[-1]['start']), d(r['end']), d(prev[-1]['end'])
        ok = any(has(t, tag, s0, e1, r['pub']) and has(t, tag, s0, e0, r['pub']) for tag in ('NetIncomeLoss', 'ProfitLoss'))
        three = any(has(t, tag, d(r['start']), e1, r['pub']) for tag in ('NetIncomeLoss', 'ProfitLoss'))
        k = '3개월 있음(다른 이유로 null)' if three else ('누계 차분 가능' if ok else '누계도 없음')
    c[k] += 1; st[k].add(t)
    if len(ex[k]) < 8: ex[k].append(f'{t} {fy}{fp}')
for k in c: print(c[k], len(st[k]), k, ex[k])
