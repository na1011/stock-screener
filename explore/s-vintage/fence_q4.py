"""펜스 술어: 최신 판에서 Q4 지배주주 = Q4 연결(0 아님)인데 같은 회계연도 최신 FY 판의 지배 ≠ 연결(1% 초과). 편입별."""
import csv, collections, sys
S = sys.argv[1]
mem = dict(l.strip().split('|') for l in open(f'{S}/members.txt'))
for p in sys.argv[2:]:
    lab = collections.defaultdict(list)
    for r in csv.DictReader(open(p), delimiter='\t'):
        if r['ticker'] in mem: lab[(r['ticker'], r['fy'], r['fp'])].append(r)
    hit = collections.defaultdict(set)
    for (t, fy, fp), v in lab.items():
        if fp != 'Q4': continue
        q = max(v, key=lambda r: r['pub']); f = lab.get((t, fy, 'FY'))
        if not f or not q['netIncome'] or not q['netIncomeControlling']: continue
        f = max(f, key=lambda r: r['pub'])
        if not f['netIncome'] or not f['netIncomeControlling']: continue
        a, b = float(f['netIncome']), float(f['netIncomeControlling'])
        if q['netIncome'] == q['netIncomeControlling'] and float(q['netIncome']) != 0 and abs(a - b) > 0.01 * max(abs(a), abs(b)):
            hit[mem[t]].add((t, fy))
    print(p.split('/')[-1], {k: (len(v), len({x[0] for x in v})) for k, v in hit.items()}, sorted(hit['current'])[:12])
