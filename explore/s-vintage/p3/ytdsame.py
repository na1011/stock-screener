"""P3b 실측 — 같은 공시 안 누계가 드러낸 앞 분기 재작성(BIIB형). 원장(.factcache) 태그 단위 · 0콜.
10-Q 공시 f가 같은 태그로 Qk(3개월)와 Ck(누계, k=2·3)를 함께 실으면 함의 C(k-1)' = Ck − Qk.
그것이 f 이전에 그 태그로 실린 C(k-1)(k=2면 Q1 3개월, k=3이면 C2)과 1%·50만 넘게 다르면 「누계가 드러낸 재작성」.
그 뒤 그 분기(k-1 · k=3이면 Q2와 Q1 중 어디인지 모름 → 「귀속 불가」)의 3개월 값이 같은 크기로 다시 실렸나(해소)도 본다."""
import collections, datetime as dt, json, sys
TAGS = ['Revenues', 'RevenueFromContractWithCustomerExcludingAssessedTax', 'NetIncomeLoss', 'ProfitLoss',
        'CostOfRevenue', 'CostOfGoodsAndServicesSold', 'OperatingIncomeLoss', 'CostsAndExpenses']
QF = {'10-Q', '10-Q/A'}
mem = dict(l.strip().split('|') for l in open(sys.argv[1]) if l.strip())
d = dt.date.fromisoformat
def near(a, b, tol=7): return abs((d(a) - d(b)).days) <= tol
res = collections.Counter(); ex = collections.defaultdict(list)
for t, m in mem.items():
    if m != 'current': continue
    try: g = json.load(open(f'/Users/na1011/dev/stock-screener/.factcache/{t}.json'))['facts'].get('us-gaap', {})
    except FileNotFoundError: continue
    for tag in TAGS:
        xs = [x for x in g.get(tag, {}).get('units', {}).get('USD', []) if 'start' in x and x.get('form') in ('10-Q', '10-Q/A', '10-K', '10-K/A')]
        byacc = collections.defaultdict(list)
        for x in xs: byacc[x['accn']].append(x)
        # 3개월 창과 누계 창을 공시마다
        for accn, fs in byacc.items():
            if fs[0]['form'] not in QF: continue
            filed = fs[0]['filed']
            q3 = [x for x in fs if 80 <= (d(x['end']) - d(x['start'])).days <= 100]
            cum = [x for x in fs if 170 <= (d(x['end']) - d(x['start'])).days <= 290]
            for c in cum:
                k = 2 if (d(c['end']) - d(c['start'])).days < 230 else 3
                q = next((x for x in q3 if x['end'] == c['end']), None)
                if q is None or 'end' not in q: continue
                imp = c['val'] - q['val']
                prev_end = d(q['start']) - dt.timedelta(days=1)
                # f 이전에 같은 태그로 실린 C(k-1): 시작 = c 시작, 끝 ≈ prev_end
                prior = [x for x in xs if x['filed'] < filed and near(x['start'], c['start']) and near(x['end'], prev_end.isoformat())]
                if not prior: continue
                pv = max(prior, key=lambda x: x['filed'])['val']
                if abs(imp - pv) > max(0.01 * max(abs(imp), abs(pv)), 5e5):
                    yr = 'cur' if d(c['end']) >= d(filed) - dt.timedelta(days=200) else 'prior'
                    res[(tag, k, yr)] += 1
                    ex[(tag, k)].append((t, c['start'], c['end'], filed, round(pv / 1e6, 1), round(imp / 1e6, 1)))
for key in sorted(res): print(res[key], key)
for key in (('Revenues', 2), ('NetIncomeLoss', 2), ('NetIncomeLoss', 3)):
    print(key, ex[key][:10])
