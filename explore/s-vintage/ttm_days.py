import sys,collections
S=sys.argv[1]; want=set(sys.argv[2].split(','))
import os; sys.argv=['x',S]+[f'{S}/'+x for x in os.environ.get('TSVS','base2.tsv,q4guard.tsv').split(',')]
src=open(f'{S}/ttm.py').read().replace("for t in cur:","for t in [x for x in cur if x in want]:").split("for p, r, w in zip(tsvs, res, wrong_st):")[0]
exec(src)
runs=collections.defaultdict(list)
for t,day,a,b in SAMPLE: runs[(t,a,b)].append(day)
for k,v in runs.items(): print(k, v[0], '~', v[-1], len(v))
