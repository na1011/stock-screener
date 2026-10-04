"""한 (종목, 회계연도)의 Q4 판마다: 그 공시일에 보이는 FY·Q1~Q3 저장값(연결 · 지배주주)과 원장 태그값(공시일 이하 최신)을 나란히."""
import csv, json, collections, sys, datetime as dt
PF = {'10-K', '10-K/A', '10-Q', '10-Q/A', '20-F', '20-F/A', '40-F', '40-F/A', '6-K', '6-K/A'}  # 어댑터 PERIODIC_FORMS와 같게 — DEF 14A 보수-성과 표가 NetIncomeLoss에 연결값을 싣는다

S, base_p, var_p, t, fy = sys.argv[1:6]
num = lambda x: float(x) if x not in ('', None) else None
d = dt.date.fromisoformat


def load(p):
    lab = collections.defaultdict(list)
    for r in csv.DictReader(open(p), delimiter='\t'):
        if r['ticker'] == t and r['fy'] == fy:
            lab[r['fp']].append(r)
    for v in lab.values():
        v.sort(key=lambda r: r['pub'])
    return lab


B, V = load(base_p), load(var_p)
g = json.load(open(f'.factcache/{t}.json'))['facts'].get('us-gaap', {})
TAGS = ('NetIncomeLoss', 'ProfitLoss', 'NetIncomeLossAttributableToNoncontrollingInterest',
        'NetIncomeLossAvailableToCommonStockholdersBasic', 'IncomeLossFromContinuingOperations')


def fact(tag, start, end, pub):
    xs = [x for x in g.get(tag, {}).get('units', {}).get('USD', [])
          if x.get('form') in PF and 'start' in x and abs((d(x['start']) - start).days) <= 7 and abs((d(x['end']) - end).days) <= 7 and x['filed'] <= pub]
    xs.sort(key=lambda x: (x['filed'], x['accn']))
    return xs[-1] if xs else None


def at(v, day):
    b = None
    for r in v:
        if r['pub'] <= day:
            b = r
    return b


m = lambda x: '' if x is None else f'{x / 1e6:,.1f}'
pubs = sorted({r['pub'] for r in B.get('Q4', [])} | {r['pub'] for r in V.get('Q4', [])})
for pub in pubs:
    print(f'== Q4 판 {pub}')
    for fp in ('FY', 'Q1', 'Q2', 'Q3', 'Q4'):
        rb, rv = at(B.get(fp, []), pub), at(V.get(fp, []), pub)
        r = rv or rb
        if r is None:
            print(f'  {fp} 판 없음')
            continue
        s, e = d(r['start']), d(r['end'])
        led = []
        for tag in TAGS:
            x = fact(tag, s, e, pub)
            led.append(f"{tag[:14]}={m(x['val']) if x else '-'}" + (f"({x['filed']})" if x else ''))
        print(f"  {fp} {r['start']}~{r['end']} pub={r['pub']} der={r.get('derived', '')} "
              f"기준선 NI={m(num(rb['netIncome'])) if rb else '?'} NIC={m(num(rb['netIncomeControlling'])) if rb else '?'} | "
              f"변형 NI={m(num(rv['netIncome'])) if rv else '?'} NIC={m(num(rv['netIncomeControlling'])) if rv else '?'}")
        print('     ' + ' '.join(led))
    # 9개월 누계
    fyr = at(V.get('FY', []), pub)
    q3 = at(V.get('Q3', []), pub)
    if fyr and q3:
        s, e = d(fyr['start']), d(q3['end'])
        print('  9M ' + ' '.join(f"{tag[:14]}={m(x['val']) if (x := fact(tag, s, e, pub)) else '-'}" for tag in TAGS))
