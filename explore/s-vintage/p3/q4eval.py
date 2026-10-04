"""P3 소비자 평가기 — Q4 정책별 TTM 유량(매출·영업이익·순이익·지배주주)을 「정리된 진실」과 대조한다(어댑터 출력 층 · 0콜).

TTM(정책) = 그날(pub ≤ day) 보이는 판의 최근 연속 네 분기 합(OFF만큼 건너뜀) — 소비자 FinancialMath와 같다:
  매출·순이익은 넷 중 하나라도 null이면 null · 영업이익은 operatingIncomeOf(유도 갈래 넷) · 지배주주는 창 단위 폴백(하나라도 null이면 순이익 합).
정리된 진실(settled) — 창 끝 분기 (Y*, Qk)에 대해 H = Y*의 첫 FY 판 공시일(그 해를 닫은 연간 보고서):
  Qk=Q4 → FY(Y*)@H · 그 밖 → FY(Y*-1)@H − Σ_{i≤k} Q_i(Y*-1)@H + Σ_{i≤k} Q_i(Y*)@H   (값@H = 공시일 ≤ H인 최신 판)
  Y*의 FY 판이 캐시에 없으면 「정리 안 됨」으로 따로 센다(판정하지 않는다).
  진실은 FY·Q1~Q3 판만 쓴다 — Q4 정책이 안 바꾸는 행이다(첫 TSV에서 읽는다 · 정책 TSV끼리 그 행이 같은지 먼저 확인한다).
틀림 = |TTM − 진실| > max(1% · max(|둘|), 50만) · 빈 = TTM null인데 진실 있음."""
import bisect, collections, csv, datetime as dt, os, sys
tsvs = sys.argv[1:-1]; out = sys.argv[-1]
POP = os.environ.get('POP'); OFF = int(os.environ.get('OFF', '0'))
d0, d1 = os.environ.get('FROM', '2022-01-03'), os.environ.get('TO', '2025-06-30')
days = []
x = dt.date.fromisoformat(d0)
while x <= dt.date.fromisoformat(d1):
    if x.weekday() < 5: days.append(x.isoformat())
    x += dt.timedelta(days=int(os.environ.get('STEP', '1')))
mem = {}
for l in open(POP):
    if l.strip():
        a, b = l.strip().split('|'); mem[a] = b
WHO = os.environ.get('WHO', 'current')
num = lambda v: float(v) if v not in ('', None) else None
def oi(r):
    v = num(r['operatingIncome'])
    if v is not None: return v
    def s(*k):
        xs = [num(r[c]) for c in k]
        return None if None in xs else sum(xs)
    def m(a, b): return None if a is None or b is None else a - b
    for v in (m(s('interestAndDividendIncome', 'noninterestIncome'), s('interestExpense', 'noninterestExpense')),
              m(num(r['revenue']), num(r['benefitsLossesAndExpenses'])),
              m(num(r['grossProfit']), num(r['operatingExpenses'])),
              m(num(r['revenue']), num(r['costsAndExpenses']))):
        if v is not None: return v
    return None
COLS = {'rev': lambda r: num(r['revenue']), 'oi': oi, 'ni': lambda r: num(r['netIncome']),
        'nic': lambda r: num(r['netIncomeControlling'])}
def load(p):
    by = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in csv.DictReader(open(p), delimiter='\t'):
        if mem.get(r['ticker']) == WHO or WHO == 'all' and r['ticker'] in mem:
            by[r['ticker']][(int(r['fy']), r['fp'])].append(r)
    for t in by:
        for v in by[t].values(): v.sort(key=lambda r: r['pub'])
    return by
data = [load(p) for p in tsvs]
TRUTH = load(os.environ['TRUTH']) if os.environ.get('TRUTH') else None   # 공시별 값 표(S_TRUTH 스윕) — 이어받기 없는 원장 값
def at(v, day):
    if not v: return None
    i = bisect.bisect_right([r['pub'] for r in v], day)
    return v[i - 1] if i else None
def ttm(qs, c):
    if c == 'nic':
        a = [COLS['nic'](q) for q in qs]
        if None not in a: return sum(a)
        c = 'ni'
    a = [COLS[c](q) for q in qs]
    return None if None in a else sum(a)
def stated(v, H, c):
    """H 이하 공시 중 그 컬럼을 실은 마지막 공시의 값(진실 표 · 행마다 그 공시가 실은 값만)."""
    best = None
    for r in v or []:
        if r['pub'] > H: break
        x = COLS[c](r)
        if x is not None: best = x
    return best
def truth(lab, y, fp, c):
    fyv = lab.get((y, 'FY'))
    if not fyv: return 'unsettled'
    H = fyv[0]['pub']
    if TRUTH is not None:
        tl = TRUTH.get(T_CUR, {})
        def val(yy, p):
            if p.startswith('Q') and p != 'Q1':
                p = 'C' + p[1]
            return stated(tl.get((yy, p)), H, c)
    else:
        def val(yy, p):
            r = at(lab.get((yy, p)), H)
            return None if r is None else COLS[c](r)
    if fp == 'Q4':
        return val(y, 'FY')
    k = int(fp[1])
    if TRUTH is not None:   # 누계: FY(Y-1) − Ck(Y-1) + Ck(Y) — C1 = Q1
        parts = [val(y - 1, 'FY'), val(y - 1, f'Q{k}'), val(y, f'Q{k}')]
        return None if None in parts else parts[0] - parts[1] + parts[2]
    parts = [val(y - 1, 'FY')] + [val(y - 1, f'Q{i}') for i in range(1, k + 1)] + [val(y, f'Q{i}') for i in range(1, k + 1)]
    if None in parts: return None
    return parts[0] - sum(parts[1:k + 1]) + sum(parts[k + 1:])
def wrong(a, b): return abs(a - b) > max(0.01 * max(abs(a), abs(b)), 5e5)
def single_truth(lab, yy, q):
    """한 분기 영업이익의 정리된 진실 — H = yy+1의 첫 FY 판 공시일(그 해 분기가 다음 해 10-Q로 다시 실린 뒤)."""
    nxt = lab.get((yy + 1, 'FY'))
    if not nxt: return 'unsettled'
    H = nxt[0]['pub']
    if TRUTH is not None:
        tl = TRUTH.get(T_CUR, {})
        cum = lambda p: stated(tl.get((yy, p)), H, 'oi')
        if q == 'Q1': return cum('Q1')
        a, b = cum('C' + str(int(q[1]) - 1) if q != 'Q2' else 'Q1') if q != 'Q4' else cum('C3'), cum('C' + q[1]) if q != 'Q4' else cum('FY')
        return None if None in (a, b) else b - a
    def val(p):
        r = at(lab.get((yy, p)), H)
        return None if r is None else oi(r)
    if q != 'Q4': return val(q)
    parts = [val('FY'), val('Q1'), val('Q2'), val('Q3')]
    return None if None in parts else parts[0] - sum(parts[1:])
def pq4_truth(lab, qs):
    vs = [single_truth(lab, q[1], q[2]) for q in qs]
    if 'unsettled' in vs: return 'unsettled'
    return None if None in vs else sum(1 for v in vs if v > 0)
res = [collections.Counter() for _ in tsvs]
trans = collections.Counter(); mag = collections.Counter(); mag_t = collections.defaultdict(collections.Counter)
cases = collections.defaultdict(lambda: collections.Counter())
w = open(out, 'w')
for t in sorted(data[0]):
    lab0 = data[0][t]; T_CUR = t
    for day in days:
        row = []
        for i, by in enumerate(data):
            lab = by.get(t, {})
            qs = []
            for (y, fp), v in lab.items():
                if fp in ('Q1', 'Q2', 'Q3', 'Q4'):
                    r = at(v, day)
                    if r: qs.append((r['end'], y, fp, r))
            qs.sort(reverse=True); qs = qs[OFF:OFF + 4]
            ok = len(qs) == 4 and all(70 <= (dt.date.fromisoformat(qs[j][0]) - dt.date.fromisoformat(qs[j + 1][0])).days <= 110 for j in range(3))
            row.append(qs if ok else None)
        if row[0] is None: continue
        y, fp = row[0][0][1], row[0][0][2]
        for c in list(COLS) + ['pq4']:
            tr = truth(lab0, y, fp, c) if c != 'pq4' else pq4_truth(lab0, row[0])
            outs = []
            for i, qs in enumerate(row):
                if qs is None: k = '네 분기 안 섬'; v = None
                elif tr == 'unsettled': k = '정리 안 됨'; v = None
                elif tr is None: k = '미정'; v = None
                else:
                    if c == 'pq4':
                        xs = [oi(q[3]) for q in qs]
                        v = None if None in xs else sum(1 for x in xs if x > 0)
                        k = '빈' if v is None else ('틀림' if v != tr else '맞음')
                    else:
                        v = ttm([q[3] for q in qs], c)
                        k = '빈' if v is None else ('틀림' if wrong(v, tr) else '맞음')
                res[i][(c, fp, k)] += 1; outs.append((k, v))
            if len(outs) == 2 and outs[0][0] != outs[1][0]:
                trans[(c, fp, outs[0][0], outs[1][0])] += 1
                cases[(c, outs[0][0], outs[1][0])][t] += 1
                w.write(f"{t}\t{day}\t{c}\t{y}{fp}\t{outs[0][0]}\t{outs[1][0]}\t{outs[0][1]}\t{outs[1][1]}\t{tr}\n")
            if c != 'pq4' and len(outs) == 2 and outs[0][0] == outs[1][0] == '틀림' and abs(outs[0][1] - outs[1][1]) > 5e5:
                e0, e1 = abs(outs[0][1] - tr), abs(outs[1][1] - tr)
                if abs(e0 - e1) > max(0.01 * max(e0, e1), 5e5):
                    mag[(c, '커짐' if e1 > e0 else '작아짐')] += 1
                    mag_t[(c, '커짐' if e1 > e0 else '작아짐')][t] += 1
for i, p in enumerate(tsvs):
    print('==', p.split('/')[-1], f'OFF={OFF} {d0}~{d1} {WHO}')
    for c in list(COLS) + ['pq4']:
        tot = collections.Counter()
        for (cc, fp, k), n in res[i].items():
            if cc == c: tot[k] += n
        byfp = {fp: res[i][(c, fp, '틀림')] for fp in ('Q1', 'Q2', 'Q3', 'Q4')}
        print(f'  {c}: {dict(tot)}  틀림(창 끝별)={byfp}')
if len(tsvs) == 2:
    print('== 갈림(기준 → 변형 · 셀-일)')
    for c in list(COLS) + ['pq4']:
        tt = {(a, b, fp): n for (cc, fp, a, b), n in trans.items() if cc == c}
        agg = collections.Counter()
        for (a, b, fp), n in tt.items(): agg[(a, b)] += n
        print(f'  {c}:', dict(agg), ' 둘 다 틀림 오차 커짐/작아짐:', mag[(c, '커짐')], '/', mag[(c, '작아짐')])
        if mag[(c, '커짐')]: print('     오차 커짐 종목 상위:', mag_t[(c, '커짐')].most_common(12))
        for (a, b), _ in agg.most_common(4):
            print(f'     {a}→{b} 종목 상위:', cases[(c, a, b)].most_common(10))
