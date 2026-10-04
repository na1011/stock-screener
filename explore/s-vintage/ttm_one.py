import sys
S, t, day = sys.argv[1], sys.argv[2], sys.argv[3]
sys.argv = ['x', S] + [f'{S}/{x}' for x in (sys.argv[4] if len(sys.argv) > 4 else 'base2.tsv,q4guard.tsv').split(',')]
src = open(f'{S}/ttm.py').read().split('res = [collections.Counter()')[0]
exec(src)
led(t)
for i, by in enumerate(data):
    lab = by.get(t, {})
    qs = []
    for (fy, fp), v in lab.items():
        if fp in ('Q1', 'Q2', 'Q3', 'Q4'):
            r = visible(v, day)
            if r: qs.append((r['end'], fy, fp, r))
    import os; o = int(os.environ.get("TTM_OFFSET", "0")); qs.sort(reverse=True); qs = qs[o:o + 4]
    print(['기준선', '변형'][i], [(q[1], q[2], q[3]['pub'], q[3]['derived'], q[3]['netIncome'], q[3]['netIncomeControlling']) for q in qs])
    print('   원장 TTM', truth(t, lab, int(qs[0][1]), qs[0][2], day))
