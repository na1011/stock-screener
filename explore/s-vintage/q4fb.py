"""바뀐 Q4 지배주주 칸(기준선 대 변형)마다 원장이 그 판 공시일에 주는 지배주주 Q4를 따로 짓는다 — 변형의 식(저장 FY − 저장 ΣQ)과 다른 재료로.

원장 지배주주 Q4(L) = 지배(FY) − 지배(9개월 누계) — 둘 다 그 Q4 판 공시일 이하에 공시된 최신 값.
  지배(창) = NetIncomeLoss 직접값, 없으면 ProfitLoss − NetIncomeLossAttributableToNoncontrollingInterest(같은 창 · 같은 공시일 조건).
원장 연결 Q4(C) = ProfitLoss(FY) − ProfitLoss(9M) — 없으면 비교 안 함.
분류: 변형 ≈ L / 기준선 ≈ L / 둘 다 / 둘 다 아님(L 있음) / L 없음(원장이 그 창 지배주주를 안 줌 · 이 도구가 us-gaap만 봄).
입력 출처는 q4prov.py와 같은 판정(저장 FY·Q1~Q3 지배주주가 NetIncomeLoss 직접값과 같은가)."""
import csv, json, collections, sys, datetime as dt
PF = {'10-K', '10-K/A', '10-Q', '10-Q/A', '20-F', '20-F/A', '40-F', '40-F/A', '6-K', '6-K/A'}  # 어댑터 PERIODIC_FORMS와 같게 — DEF 14A 보수-성과 표가 NetIncomeLoss에 연결값을 싣는다

S, base_p, var_p, out_p = sys.argv[1:5]
mem = dict(l.strip().split('|') for l in open(f'{S}/members.txt'))


def load(p):
    return {(r['ticker'], r['fy'], r['fp'], r['pub']): r for r in csv.DictReader(open(p), delimiter='\t') if r['ticker'] in mem}


B, V = load(base_p), load(var_p)
lab = collections.defaultdict(list)
for k, r in V.items():
    lab[k[:3]].append(r)
for v in lab.values():
    v.sort(key=lambda r: r['pub'])
num = lambda x: float(x) if x not in ('', None) else None
d = dt.date.fromisoformat
cache = {}


def g(t):
    if t not in cache:
        cache[t] = json.load(open(f'.factcache/{t}.json'))['facts'].get('us-gaap', {})
    return cache[t]


def fact(t, tag, start, end, pub):
    xs = [x for x in g(t).get(tag, {}).get('units', {}).get('USD', [])
          if x.get('form') in PF and 'start' in x and abs((d(x['start']) - start).days) <= 7 and abs((d(x['end']) - end).days) <= 7 and x['filed'] <= pub]
    xs.sort(key=lambda x: (x['filed'], x['accn']))
    return xs[-1] if xs else None


def ctrl(t, start, end, pub):
    x = fact(t, 'NetIncomeLoss', start, end, pub)
    if x:
        return x['val'], 'NIL'
    p, n = fact(t, 'ProfitLoss', start, end, pub), fact(t, 'NetIncomeLossAttributableToNoncontrollingInterest', start, end, pub)
    if p and n:
        return p['val'] - n['val'], 'PL-NCI'
    return None, None


def at(v, day):
    b = None
    for r in v:
        if r['pub'] <= day:
            b = r
    return b


close = lambda a, b: a is not None and b is not None and abs(a - b) <= max(0.01 * max(abs(a), abs(b)), 1e6)
cls = collections.Counter()
st = collections.defaultdict(set)
w = csv.writer(open(out_p, 'w'), delimiter='\t')
w.writerow(['ticker', 'fy', 'pub', 'prov', 'base', 'var', 'L', 'Lsrc', 'C', 'nciQ4', 'cls'])
for k in sorted(B.keys() & V.keys()):
    if k[2] != 'Q4' or B[k]['netIncomeControlling'] == V[k]['netIncomeControlling']:
        continue
    t, fy, _, pub = k
    rows = [at(lab.get((t, fy, q), []), pub) for q in ('FY', 'Q1', 'Q2', 'Q3')]
    b, v = num(B[k]['netIncomeControlling']), num(V[k]['netIncomeControlling'])
    if any(r is None for r in rows):
        prov = '입력 판 없음'
        L = Lsrc = C = nq = None
    else:
        ok = []
        for r in rows:
            x = fact(t, 'NetIncomeLoss', d(r['start']), d(r['end']), pub)
            ok.append(close(num(r['netIncomeControlling']), x['val'] if x else None))
        prov = '직접' if all(ok) else f'폴백{4 - sum(ok)}'
        fys, fye, q3e = d(rows[0]['start']), d(rows[0]['end']), d(rows[3]['end'])
        a, sa = ctrl(t, fys, fye, pub)
        c9, s9 = ctrl(t, fys, q3e, pub)
        L = a - c9 if a is not None and c9 is not None else None
        Lsrc = f'{sa}/{s9}' if L is not None else None
        pa, p9 = fact(t, 'ProfitLoss', fys, fye, pub), fact(t, 'ProfitLoss', fys, q3e, pub)
        C = pa['val'] - p9['val'] if pa and p9 else None
        na, n9 = (fact(t, 'NetIncomeLossAttributableToNoncontrollingInterest', fys, e, pub) for e in (fye, q3e))
        nq = na['val'] - n9['val'] if na and n9 else None
    if L is None:
        c = 'L없음'
    else:
        vb, vv = close(b, L), close(v, L)
        c = '둘다' if vb and vv else ('변형' if vv else ('기준선' if vb else '둘다아님'))
    flip = b is not None and v is not None and (b > 0) != (v > 0)
    key = ('직접' if prov == '직접' else '폴백섞임', c)
    cls[key] += 1
    st[key].add(t)
    w.writerow([t, fy, pub, prov, b, v, L, Lsrc, C, nq, c + ('·부호반전' if flip else '')])
for kk in sorted(cls):
    print(f'{cls[kk]:5} {len(st[kk]):4}종목 {kk}')
