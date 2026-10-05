# SUN LP 단위 태그 대 분배금/주당분배 역산 (같은 companyfacts 파일 · 외부 호출 0) · 대조군 ET(dei 대 LP 태그)
import json
F='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/a2/out-facts/'
s=json.load(open(F+'facts-1552275.json'))['facts']['us-gaap']
def pick(d,k,u,end,start=None):
    for x in d[k]['units'][u]:
        if x['end']==end and x.get('start')==start: return x['val']
lp=pick(s,'LimitedPartnersCapitalAccountUnitsOutstanding','shares','2025-12-31')
paid6=pick(s,'DistributionMadeToLimitedPartnerCashDistributionsPaid','USD','2026-06-30','2026-01-01')
dpu6=[x['val'] for x in s['DistributionsPerLimitedPartnershipUnitOutstanding']['units']['USD/shares'] if x['end']=='2026-06-30' and x.get('frame') is None][0]
paidFY=pick(s,'DistributionMadeToLimitedPartnerCashDistributionsPaid','USD','2025-12-31','2025-01-01')
dpuFY=pick(s,'DistributionsPerLimitedPartnershipUnitOutstanding','USD/shares','2025-12-31','2025-01-01')
print('SUN LP tag 2025-12-31', lp)
print('SUN 6M2026 paid/DPU', paid6, dpu6, round(paid6/dpu6/1e6,1),'M')
print('SUN FY2025 paid/DPU', paidFY, dpuFY, round(paidFY/dpuFY/1e6,1),'M')
close=74.73
print('cap @74.73: tag', round(lp*close/1e9,2),'B · implied', round(paid6/dpu6*close/1e9,2),'B · boundary 5.24B')
e=json.load(open(F+'facts-1276187.json'))['facts']
print('ET dei 2026-07-31', [x['val'] for x in e['dei']['EntityCommonStockSharesOutstanding']['units']['shares'] if x['end']=='2026-07-31'][0],
      'ET LP tag 2026-06-30', pick(e['us-gaap'],'LimitedPartnersCapitalAccountUnitsOutstanding','shares','2026-06-30'))
