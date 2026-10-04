"""P3 실측 1 — FY 공시 뒤 그 해 분기가 값이 바뀐 판으로 다시 나오는 현상의 모집단(어댑터 출력 층 · 0콜).
입력: 스윕 TSV(S0만 켠 상태) · members.txt(ticker|current/past).
사건 = (종목, FY Y, 컬럼): Y의 첫 FY 판 공시일 pF0 뒤에 Q1~Q3 중 하나가 pF0 시점 값과 1%·50만 넘게 다른(또는 null→값) 판으로 나옴.
사후 분류: Y의 FY가 pF0 뒤에 같은 컬럼에서 바뀌었나(바뀜 = 기준 변경이 FY 뒤에도 일어남 · 안 바뀜 = FY가 이미 새 기준이었을 가능성).
시점 신호 S1: pF0 공시(첫 FY 판)가 전년 FY(Y-1) 같은 컬럼을 그 직전 보이던 값과 다르게 실었나."""
import collections, csv, sys
tsv, members = sys.argv[1], sys.argv[2]
mem = dict(l.strip().split('|') for l in open(members) if l.strip())
num = lambda x: float(x) if x not in ('', None) else None
def oi(r):
    v = num(r['operatingIncome'])
    if v is not None: return v
    def s(*k):
        xs = [num(r[c]) for c in k]
        return None if None in xs else sum(xs)
    def m(a, b): return None if a is None or b is None else a - b
    for x in (m(s('interestAndDividendIncome', 'noninterestIncome'), s('interestExpense', 'noninterestExpense')),
              m(num(r['revenue']), num(r['benefitsLossesAndExpenses'])),
              m(num(r['grossProfit']), num(r['operatingExpenses'])),
              m(num(r['revenue']), num(r['costsAndExpenses']))):
        if x is not None: return x
    return None
COLS = {'revenue': lambda r: num(r['revenue']), 'oi': oi, 'ni': lambda r: num(r['netIncome']),
        'nic': lambda r: num(r['netIncomeControlling'])}
def diff(a, b):
    if a is None and b is None: return False
    if a is None or b is None: return True
    return abs(a - b) > max(0.01 * max(abs(a), abs(b)), 5e5)
by = collections.defaultdict(lambda: collections.defaultdict(list))
for r in csv.DictReader(open(tsv), delimiter='\t'):
    if r['ticker'] in mem:
        by[r['ticker']][(int(r['fy']), r['fp'])].append(r)
def at(v, p):
    b = None
    for r in sorted(v, key=lambda r: r['pub']):
        if r['pub'] <= p: b = r
    return b
ev = []
for t, lab in by.items():
    for (y, fp), fv in lab.items():
        if fp != 'FY' or y < 2015: continue
        fv = sorted(fv, key=lambda r: r['pub'])
        p0 = fv[0]['pub']
        qs = [lab.get((y, q)) for q in ('Q1', 'Q2', 'Q3')]
        if None in qs: continue
        for c, f in COLS.items():
            refiles = []
            for qi, qv in enumerate(qs):
                base = at(qv, p0)
                if base is None: continue
                for r in sorted(qv, key=lambda r: r['pub']):
                    if r['pub'] > p0 and diff(f(base), f(r)):
                        refiles.append((r['pub'], qi + 1, f(base), f(r)))
                        break
            if not refiles: continue
            fin = f(fv[-1]); f0 = f(fv[0])
            fchg = [r['pub'] for r in fv[1:] if diff(f(r), f0)]
            prev = lab.get((y - 1, 'FY'))
            s1 = None
            if prev:
                before, atp0 = at(prev, str(p0)[:10] if False else p0), None
                bef = [r for r in sorted(prev, key=lambda r: r['pub']) if r['pub'] < p0]
                atp = [r for r in prev if r['pub'] == p0]
                if bef and atp:
                    s1 = diff(f(bef[-1]), f(atp[0]))
                elif bef:
                    s1 = False
            first = min(x[0] for x in refiles)
            klass = '안 바뀜' if not fchg else ('FY가 분기 재발행 뒤 바뀜' if min(fchg) > first else 'FY가 분기 재발행 전에도 바뀜')
            ev.append(dict(t=t, mem=mem[t], y=y, c=c, n=len(refiles), first=first, klass=klass, s1=s1, fchg=fchg[:1],
                           q=[(x[1], x[0]) for x in refiles]))
w = csv.writer(open(sys.argv[3], 'w'), delimiter='\t')
w.writerow(['ticker', 'mem', 'fy', 'col', 'nq', 'first_refile', 'post', 's1', 'fy_change', 'quarters'])
for e in ev:
    w.writerow([e['t'], e['mem'], e['y'], e['c'], e['n'], e['first'], e['klass'], e['s1'], e['fchg'], e['q']])
for m_ in ('current', 'past'):
    print('==', m_)
    for c in COLS:
        es = [e for e in ev if e['mem'] == m_ and e['c'] == c]
        cnt = collections.Counter((e['klass'], e['s1']) for e in es)
        yrs = collections.Counter('2020+' if e['y'] >= 2020 else '~2019' for e in es)
        print(f"  {c}: 사건 {len(es)} · 종목 {len({e['t'] for e in es})} · {dict(yrs)}")
        for k, v in sorted(cnt.items(), key=lambda kv: -kv[1]):
            print(f"     사후={k[0]:<22} S1={k[1]}: {v}")
