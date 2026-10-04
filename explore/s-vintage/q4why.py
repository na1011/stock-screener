"""q4fb.tsv의 칸마다 입력 넷(FY·Q1~Q3 저장 지배주주)이 원장의 무엇과 같은지 — 어긋난 입력의 정체를 센다.
태그 판정(그 Q4 판 공시일 이하 최신): NIL=NetIncomeLoss · PLN=ProfitLoss − 비지배 · PLN+=ProfitLoss + 비지배(부호 반대) · AC=보통주 귀속 · PL=연결."""
import csv, json, collections, sys, datetime as dt
PF = {'10-K', '10-K/A', '10-Q', '10-Q/A', '20-F', '20-F/A', '40-F', '40-F/A', '6-K', '6-K/A'}  # 어댑터 PERIODIC_FORMS와 같게 — DEF 14A 보수-성과 표가 NetIncomeLoss에 연결값을 싣는다

S, var_p, fb_p = sys.argv[1:4]
num = lambda x: float(x) if x not in ('', None) else None
d = dt.date.fromisoformat
lab = collections.defaultdict(list)
for r in csv.DictReader(open(var_p), delimiter='\t'):
    lab[(r['ticker'], r['fy'], r['fp'])].append(r)
for v in lab.values():
    v.sort(key=lambda r: r['pub'])
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


def at(v, day):
    b = None
    for r in v:
        if r['pub'] <= day:
            b = r
    return b


close = lambda a, b: a is not None and b is not None and abs(a - b) <= max(0.01 * max(abs(a), abs(b)), 1e6)


def what(t, r, pub):
    s, e = d(r['start']), d(r['end'])
    v = num(r['netIncomeControlling'])
    nil, pl, nci, ac = (fact(t, tag, s, e, pub) for tag in (
        'NetIncomeLoss', 'ProfitLoss', 'NetIncomeLossAttributableToNoncontrollingInterest', 'NetIncomeLossAvailableToCommonStockholdersBasic'))
    if v is None:
        return 'null'
    if close(v, nil):
        return 'NIL'
    if nil is None and pl is not None and nci is not None and close(v, pl - nci):
        return 'PLN'
    if pl is not None and nci is not None and close(v, pl + nci) and not close(v, pl):
        return 'PLN+'
    if close(v, ac):
        return 'AC'
    if close(v, pl):
        return 'PL'
    return '기타'


agg = collections.Counter()
ex = collections.defaultdict(list)
for r in csv.DictReader(open(fb_p), delimiter='\t'):
    t, fy, pub = r['ticker'], r['fy'], r['pub']
    rows = [at(lab.get((t, fy, q), []), pub) for q in ('FY', 'Q1', 'Q2', 'Q3')]
    if any(x is None for x in rows):
        sig = '입력 판 없음'
    else:
        sig = ' '.join(f'{q}:{what(t, x, pub)}' for q, x in zip(('FY', 'Q1', 'Q2', 'Q3'), rows))
    c = r['cls'].split('·')[0]
    if r['prov'] == '직접' and c in ('변형', '둘다'):
        continue
    kinds = tuple(sorted({p.split(':')[1] for p in sig.split()} - {'NIL'})) if sig != '입력 판 없음' else ('입력판없음',)
    agg[(c, kinds)] += 1
    if len(ex[(c, kinds)]) < 3:
        ex[(c, kinds)].append(f'{t} {fy} {pub}')
for k in sorted(agg, key=lambda k: (k[0], -agg[k])):
    print(f'{agg[k]:4} {k[0]:6} {"+".join(k[1]) or "(전부 NIL)"}  예: {", ".join(ex[k])}')
