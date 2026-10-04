"""분기 지배주주 폴백 모집단(fallback.py와 같은 술어 + 양식 필터)에서 좁힌 보통주 귀속 술어를 사후 정답(뒤 공시 NetIncomeLoss)과 견준다.
술어 P: 그 행 공시일 이하에 공시된 가장 늦은 FY에서 NetIncomeLoss == 보통주 귀속(정확히) — 우선주배당 0의 시점 정합 증거.
P가 서고 그 분기 보통주 귀속이 있으면 후보 = 보통주 귀속. 평가에만 사후 값을 쓴다(규칙에는 안 쓴다)."""
import csv, json, collections, sys, datetime as dt
PF = {'10-K', '10-K/A', '10-Q', '10-Q/A', '20-F', '20-F/A', '40-F', '40-F/A', '6-K', '6-K/A'}
S = sys.argv[1]
mem = dict(l.strip().split('|') for l in open(f'{S}/members.txt'))
d = dt.date.fromisoformat
NCI = ['NetIncomeLossAttributableToNoncontrollingInterest', 'NetIncomeLossAttributableToRedeemableNoncontrollingInterest',
       'NetIncomeLossAttributableToNonredeemableNoncontrollingInterest']
cache = {}
def facts(t, tag):
    if t not in cache:
        cache.clear(); cache[t] = json.load(open(f'.factcache/{t}.json'))['facts'].get('us-gaap', {})
    return [x for x in cache[t].get(tag, {}).get('units', {}).get('USD', []) if 'start' in x and x.get('form') in PF]
def win(t, tag, s, e, pub=None, after=None):
    xs = [x for x in facts(t, tag) if abs((d(x['start']) - d(s)).days) <= 7 and abs((d(x['end']) - d(e)).days) <= 7
          and (pub is None or x['filed'] <= pub) and (after is None or x['filed'] > after)]
    xs.sort(key=lambda x: (x['filed'], x['accn']))
    return xs
def nci_pit(t, pub):
    return any(x['filed'] <= pub and x['val'] != 0 for tag in NCI for x in facts(t, tag))
def no_pd_pit(t, pub):
    """공시일 이하 가장 늦게 공시된 FY 창(330~380일)의 NetIncomeLoss와 보통주 귀속이 같은가 — 같은 공시 안에서."""
    fy = [x for x in facts(t, 'NetIncomeLoss') if x['filed'] <= pub and 330 <= (d(x['end']) - d(x['start'])).days <= 380]
    if not fy: return None
    last = max(fy, key=lambda x: (x['end'], x['filed']))
    ac = [x for x in facts(t, 'NetIncomeLossAvailableToCommonStockholdersBasic') if x['accn'] == last['accn']
          and x['start'] == last['start'] and x['end'] == last['end']]
    if not ac: return None
    return ac[0]['val'] == last['val']
num = lambda x: float(x) if x else None
close = lambda a, b: a is not None and b is not None and abs(a - b) <= max(0.01 * max(abs(a), abs(b)), 1e6)
res = collections.Counter(); st = collections.defaultdict(set); ex = collections.defaultdict(list)
rows = sorted((r for r in csv.DictReader(open(f'{S}/base2.tsv'), delimiter='\t') if r['ticker'] in mem), key=lambda r: r['ticker'])
for r in rows:
    t = r['ticker']
    if r['fp'] == 'Q4' or r['derived'] == 'true' or not r['netIncomeControlling'] or r['netIncomeControlling'] != r['netIncome']:
        continue
    pub = r['pub']
    if win(t, 'NetIncomeLoss', r['start'], r['end'], pub) or not nci_pit(t, pub):
        continue
    truth = win(t, 'NetIncomeLoss', r['start'], r['end'], after=pub)
    truth = truth[-1]['val'] if truth else None
    ac = win(t, 'NetIncomeLossAvailableToCommonStockholdersBasic', r['start'], r['end'], pub)
    ac = ac[-1]['val'] if ac else None
    p = no_pd_pit(t, pub)
    cons = num(r['netIncome'])
    gate = 'P 섬' if (p and ac is not None) else ('P 안 섬' if ac is not None else '보통주 없음')
    if truth is None:
        v = '사후 정답 없음'
    else:
        v = ('보통주 맞음' if close(ac, truth) else '보통주 틀림') + ' / ' + ('연결 맞음' if close(cons, truth) else '연결 틀림')
    k = (gate, v, r['fp'] == 'FY')
    res[k] += 1; st[k].add(t)
    if len(ex[k]) < 4: ex[k].append((t, r['fy'], r['fp'], pub, cons and cons / 1e6, ac and ac / 1e6, truth and truth / 1e6))
for k in sorted(res, key=lambda k: (k[0], -res[k])):
    print(f'{res[k]:5} {len(st[k]):3}종목 {k} {ex[k][:3]}')
