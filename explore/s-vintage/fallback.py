"""기준선에서 지배주주 자리에 연결이 들어간 소스 FY·분기 행 — 후보 값들을 사후 정답(뒤 공시 NetIncomeLoss)과 견준다."""
import csv, json, collections, sys, datetime as dt
S = sys.argv[1]
mem = dict(l.strip().split('|') for l in open(f'{S}/members.txt'))
d = dt.date.fromisoformat
NCI = ['NetIncomeLossAttributableToNoncontrollingInterest', 'NetIncomeLossAttributableToRedeemableNoncontrollingInterest',
       'NetIncomeLossAttributableToNonredeemableNoncontrollingInterest']
PD = ['PreferredStockDividendsIncomeStatementImpact', 'DividendsPreferredStock', 'PreferredStockDividendsAndOtherAdjustments']
cache = {}
def facts(t, tag):
    if t not in cache:
        cache[t] = json.load(open(f'.factcache/{t}.json'))['facts'].get('us-gaap', {})
    return [x for x in cache[t].get(tag, {}).get('units', {}).get('USD', []) if 'start' in x]
def win(t, tag, s, e, pub=None, after=None):
    xs = [x for x in facts(t, tag) if abs((d(x['start']) - d(s)).days) <= 7 and abs((d(x['end']) - d(e)).days) <= 7
          and (pub is None or x['filed'] <= pub) and (after is None or x['filed'] > after)]
    xs.sort(key=lambda x: x['filed'])
    return xs
def nci_pit(t, pub):
    """그 판 공시일 이하에 공시된, 0이 아닌 비지배 손익이 하나라도 있는가."""
    return any(x['filed'] <= pub and x['val'] != 0 for tag in NCI for x in facts(t, tag))
num = lambda x: float(x) if x else None
close = lambda a, b: a is not None and b is not None and abs(a - b) <= max(0.01 * max(abs(a), abs(b)), 1e6)
res = collections.Counter(); st = collections.defaultdict(set); ex = collections.defaultdict(list)
for r in csv.DictReader(open(f'{S}/base2.tsv'), delimiter='\t'):
    t = r['ticker']
    if t not in mem or r['fp'] == 'Q4' or not r['netIncomeControlling'] or r['netIncomeControlling'] != r['netIncome']:
        continue
    pub = r['pub']
    if win(t, 'NetIncomeLoss', r['start'], r['end'], pub):
        continue            # 직접 태그가 있으면 폴백이 아니다
    if not nci_pit(t, pub):
        continue            # 그 시점에 비지배가 안 보인다 → 연결 = 지배주주로 두는 것이 정당
    truth = win(t, 'NetIncomeLoss', r['start'], r['end'], after=pub)
    truth = truth[-1]['val'] if truth else None
    ac = win(t, 'NetIncomeLossAvailableToCommonStockholdersBasic', r['start'], r['end'], pub)
    ac = ac[-1]['val'] if ac else None
    pd = None
    for tag in PD:
        xs = win(t, tag, r['start'], r['end'], pub)
        if xs:
            pd = xs[-1]['val']; break
    cons = num(r['netIncome'])
    cand = ac + pd if (ac is not None and pd is not None) else ac
    kind = ('보통주+우선주' if pd is not None else '보통주만') if ac is not None else '후보 없음'
    if truth is None:
        verdict = '사후 정답 없음'
    else:
        verdict = ('후보 맞음' if close(cand, truth) else '후보 틀림') + ' / ' + ('연결 맞음' if close(cons, truth) else '연결 틀림')
    key = (kind, verdict)
    res[key] += 1; st[key].add(t)
    if len(ex[key]) < 3: ex[key].append((t, r['fy'], r['fp'], pub, cons, cand, truth))
for k in sorted(res, key=lambda k: -res[k]):
    print(f'{res[k]:5} {len(st[k]):3}종목 {k} {ex[k][:2]}')
