"""저장 지배주주 순이익(소스 행 FY·Q1~Q3)이 같은 창의 원장 태그끼리 모순되는 자리 — 판정 없는 검출기.
그 행 공시일 이하 최신 팩트로 본다(시점 정합). 기간 끝 ≥ 2020-07-01 · 편입(현·과거).
  M1 NIL≠항등식·보통주≈항등식 : 저장 = NetIncomeLoss, ProfitLoss − 비지배와 1% 넘게 다르고 보통주 귀속은 항등식과 1% 안 (AMRX형)
  M2 NIL=보통주≠연결·비지배없음 : 저장 = NetIncomeLoss = 보통주 귀속(정확히), ProfitLoss와 다르고 비지배 태그가 없거나 0 (OXY형 — 우선주배당 뒤 값)
  M3 비지배 부호 : 저장 = ProfitLoss − 비지배(NetIncomeLoss 없음)인데 보통주 귀속이 ProfitLoss + 비지배와 0.1% 안이고 항등식과는 1% 넘게 다름 (FCX형)
  M4 NIL≠항등식·보통주 없음/불일치 : M1의 나머지(우선주배당 정상인 ACGL형이 섞인다 — 판정 아님)"""
import csv, json, collections, sys, datetime as dt
PF = {'10-K', '10-K/A', '10-Q', '10-Q/A', '20-F', '20-F/A', '40-F', '40-F/A', '6-K', '6-K/A'}  # 어댑터 PERIODIC_FORMS와 같게 — DEF 14A 보수-성과 표가 NetIncomeLoss에 연결값을 싣는다

S, var_p = sys.argv[1:3]
mem = dict(l.strip().split('|') for l in open(f'{S}/members.txt'))
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
    return xs[-1]['val'] if xs else None


def close(a, b, r=0.01, floor=1e6):
    return a is not None and b is not None and abs(a - b) <= max(r * max(abs(a), abs(b)), floor)


cnt = collections.Counter()
st = collections.defaultdict(set)
ex = collections.defaultdict(list)
tot = 0
for r in csv.DictReader(open(var_p), delimiter='\t'):
    if r['ticker'] not in mem or r['derived'] == 'true' or r['fp'] == 'Q4' or r['end'] < '2020-07-01':
        continue
    v = num(r['netIncomeControlling'])
    if v is None:
        continue
    tot += 1
    t, s, e, pub = r['ticker'], d(r['start']), d(r['end']), r['pub']
    nil, pl, nci, ac = (fact(t, tag, s, e, pub) for tag in (
        'NetIncomeLoss', 'ProfitLoss', 'NetIncomeLossAttributableToNoncontrollingInterest', 'NetIncomeLossAvailableToCommonStockholdersBasic'))
    ident = pl - nci if pl is not None and nci is not None else None
    k = None
    if close(v, nil, 0, 0):
        if ident is not None and not close(nil, ident):
            k = 'M1 NIL≠항등식·보통주≈항등식' if close(ac, ident) else 'M4 NIL≠항등식·나머지'
        elif pl is not None and (nci is None or nci == 0) and ac is not None and nil == ac and not close(nil, pl):
            k = 'M2 NIL=보통주≠연결·비지배없음'
    elif ident is not None and close(v, ident, 0, 0) and nil is None and nci:
        if close(ac, pl + nci, 0.001, 0) and not close(ac, ident):
            k = 'M3 비지배 부호'
    if k:
        cnt[(k, r['fp'] == 'FY')] += 1
        st[k].add(t)
        if len(ex[k]) < 6:
            ex[k].append(f"{t} {r['fy']}{r['fp']} 저장={v / 1e6:,.1f} NIL={'' if nil is None else f'{nil / 1e6:,.1f}'} PL={'' if pl is None else f'{pl / 1e6:,.1f}'} NCI={'' if nci is None else f'{nci / 1e6:,.1f}'} AC={'' if ac is None else f'{ac / 1e6:,.1f}'}")
print('대상 소스 행(저장 지배주주 있음):', tot)
for k in sorted(st):
    print(f'{k}: FY {cnt[(k, True)]} · 분기 {cnt[(k, False)]} · {len(st[k])}종목')
    for x in ex[k]:
        print('   ', x)
