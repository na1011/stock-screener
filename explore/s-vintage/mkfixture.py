"""S0 시험 픽스처 — .factcache companyfacts를 순이익·자본·자산·부채·매출 태그와 기간으로 잘라 낸다(BX 픽스처와 같은 모양)."""
import json, sys
TAGS = ['ProfitLoss', 'NetIncomeLoss', 'NetIncomeLossAttributableToNoncontrollingInterest',
        'NetIncomeLossAttributableToRedeemableNoncontrollingInterest', 'NetIncomeLossAttributableToNonredeemableNoncontrollingInterest',
        'NetIncomeLossAvailableToCommonStockholdersBasic', 'StockholdersEquity',
        'StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest', 'Assets', 'Liabilities',
        'Revenues', 'RevenueFromContractWithCustomerExcludingAssessedTax']
t, since, until, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
d = json.load(open(f'.factcache/{t}.json'))
g = d['facts']['us-gaap']
keep = {}
for tag in TAGS:
    if tag not in g: continue
    units = {}
    for u, xs in g[tag]['units'].items():
        ys = [x for x in xs if since <= x['end'] <= until and x['filed'] <= '2026-12-31']
        if ys: units[u] = ys
    if units: keep[tag] = {'units': units}
json.dump({'cik': d['cik'], 'entityName': d['entityName'], 'facts': {'us-gaap': keep}}, open(out, 'w'), ensure_ascii=False, indent=1)
print(t, {k: sum(len(v) for v in keep[k]['units'].values()) for k in keep})
