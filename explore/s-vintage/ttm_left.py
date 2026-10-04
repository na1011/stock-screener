import sys,collections
S=sys.argv[1]
sys.argv=['x',S,f'{S}/'+(sys.argv[2] if len(sys.argv)>2 else 'q4guard.tsv')]
src=open(f'{S}/ttm.py').read().split("for p, r, w in zip(tsvs, res, wrong_st):")[0]
src=src.replace("            outs.append(c)\n","            outs.append(c)\n            if c in ('틀림','빈'): LEFT[(t,c)].append((day, ttm, tr, [(q[1],q[2]) for q in qs]))\n")
LEFT=collections.defaultdict(list)
exec(src)
for (t,c),v in sorted(LEFT.items(), key=lambda x:-len(x[1])):
    d0=v[0]; print(f"{t:6} {c} {len(v):4}일 {d0[0]}~{v[-1][0]} TTM={"" if d0[1] is None else f"{d0[1]/1e6:,.1f}"} 원장={d0[2]/1e6:,.1f} 최근={d0[3][0]}")
