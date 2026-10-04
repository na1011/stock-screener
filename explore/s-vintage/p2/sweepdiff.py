"""두 스윕 TSV의 차이 — 판 집합(더함/뺌)과 공통 판의 칸 변화(컬럼 · 기간 종류별) · 종목 수 · 예."""
import csv, collections, sys
a, b = sys.argv[1], sys.argv[2]
mem = dict(l.strip().split('|') for l in open(sys.argv[3]) if l.strip())
def load(p):
    d = {}
    for r in csv.DictReader(open(p), delimiter='\t'):
        d[(r['ticker'], r['fy'], r['fp'], r['pub'])] = r
    return d
A, B = load(a), load(b)
SKIP = {'ticker', 'fy', 'fp', 'start', 'end', 'pub', 'rc', 'ccy', 'derived'}
add = [k for k in B if k not in A]; rem = [k for k in A if k not in B]
cells = collections.Counter(); stocks = collections.defaultdict(set); ex = collections.defaultdict(list)
for k in set(A) & set(B):
    for c in A[k]:
        if c in SKIP or A[k][c] == B[k][c]: continue
        kind = 'FY' if k[2] == 'FY' else ('Q4' if k[2] == 'Q4' else 'Q1-3')
        g = mem.get(k[0], 'other')
        cells[(c, kind, g)] += 1; stocks[(c, kind, g)].add(k[0]); ex[(c, kind)].append((k, A[k][c], B[k][c]))
print('판 더함', len(add), '종목', len({k[0] for k in add}), '· 판 뺌', len(rem), '종목', len({k[0] for k in rem}))
print('  더함 기간:', collections.Counter(k[2] for k in add).most_common(), ' 뺌 기간:', collections.Counter(k[2] for k in rem).most_common())
for key, n in sorted(cells.items(), key=lambda kv: -kv[1])[:30]:
    print(f'  {n:5} 칸 · {len(stocks[key]):4} 종목  {key}')
for key in [('revenue', 'FY'), ('netIncome', 'FY'), ('revenue', 'Q4'), ('netIncomeControlling', 'Q4')]:
    print(key, ex[key][:5])
print('더함 예:', add[:10]); print('뺌 예:', rem[:10])
