"""P3 실측 2 — 시점 신호의 정밀도·재현율(사후 분류 대비). pop.py 사건마다:
S1  = Y의 첫 FY 공시(pF0)가 전년 FY(Y-1) 같은 컬럼을 그 직전 보이던 값과 다르게 실었다.
S1* = S1 ∧ Y의 분기 공시(pF0(Y-1) < pub < pF0(Y))가 Y-1 분기 비교열을 같은 컬럼에서 다시 싣지 않았다
      (기준 변경이 Y 중에 이미 일어나 Y 분기가 새 기준인 AIG LDTI형을 뺀다 — 기준 변경이 Q3 10-Q와 10-K 사이에 있었다는 신호).
사후: FY(Y)가 첫 분기 재발행 뒤 같은 컬럼에서 바뀌었나."""
import collections, csv, sys
sys.argv = [sys.argv[0]] + sys.argv[1:]
exec(open(sys.argv[0].replace('sig.py', 'pop.py')).read().split("ev = []")[0])
res = collections.Counter(); per = collections.defaultdict(list)
for t, lab in by.items():
    for (y, fp), fv in lab.items():
        if fp != 'FY' or y < 2015: continue
        fv = sorted(fv, key=lambda r: r['pub']); p0 = fv[0]['pub']
        qs = [lab.get((y, q)) for q in ('Q1', 'Q2', 'Q3')]
        if None in qs: continue
        prev = lab.get((y - 1, 'FY'))
        for c, f in COLS.items():
            ref = []
            for qi, qv in enumerate(qs):
                b = at(qv, p0)
                if b is None: continue
                for r in sorted(qv, key=lambda r: r['pub']):
                    if r['pub'] > p0 and diff(f(b), f(r)): ref.append(r['pub']); break
            if not ref: continue
            first = min(ref)
            fchg = [r['pub'] for r in fv[1:] if diff(f(r), f(fv[0]))]
            post = 'A?(FY 안 바뀜)' if not fchg else ('B?(FY 뒤에 바뀜)' if min(fchg) > first else '혼합')
            s1 = None
            if prev:
                prev = sorted(prev, key=lambda r: r['pub'])
                bef = [r for r in prev if r['pub'] < p0]; atp = [r for r in prev if r['pub'] == p0]
                s1 = bool(bef and atp and diff(f(bef[-1]), f(atp[0])))
                pp0 = prev[0]['pub']
                during = False
                for q in ('Q1', 'Q2', 'Q3'):
                    qv = sorted(lab.get((y - 1, q), []), key=lambda r: r['pub'])
                    b = at(qv, pp0)
                    if b is None: continue
                    if any(pp0 < r['pub'] < p0 and diff(f(b), f(r)) for r in qv): during = True
                s1s = s1 and not during
            else:
                s1s = None
            res[(mem[t], c, post, s1, s1s)] += 1
            per[(mem[t], c, post, s1s)].append((t, y))
for m_ in ('current',):
    for c in COLS:
        print(f'== {m_} {c}')
        tot = collections.Counter(); 
        for (mm, cc, post, s1, s1s), n in res.items():
            if mm == m_ and cc == c:
                tot[(post, 'S1', s1)] += n; tot[(post, 'S1*', s1s)] += n
        for sig in ('S1', 'S1*'):
            tp = tot[('A?(FY 안 바뀜)', sig, True)]; fp_ = tot[('B?(FY 뒤에 바뀜)', sig, True)] + tot[('혼합', sig, True)]
            pos = sum(n for (p, s, v), n in tot.items() if s == sig and p == 'A?(FY 안 바뀜)')
            print(f'   {sig}: 참→A {tp} · 참→B/혼합 {fp_} · 정밀도 {tp / max(1, tp + fp_):.0%} · A 재현율 {tp / max(1, pos):.0%}  (A 전체 {pos})')
        print('   S1* 참 · B 사례:', per[(m_, c, 'B?(FY 뒤에 바뀜)', True)][:12])
