# 0콜: 83 CIK 캐시(ga/out2)에서 v3 보충 폼 집합(6-K 뺌)과 PERIODIC_FORMS(6-K 포함)의 차이 —
# 6-K·6-K/A · isXBRL=1 · companyfacts에 그 accn us-gaap 0 · filingDate>=2018
import json, glob, collections, os
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
c=collections.Counter(); who=collections.Counter(); names={}
for sf in sorted(glob.glob(S+'subs-*.json')):
    k=os.path.basename(sf)[5:-5]
    j=json.load(open(sf)); s=j['filings']['recent']; names[k]=j.get('tickers')
    f=json.load(open(S+f'facts-{k}.json'))['facts']
    acc_any={x['accn'] for tx in f.values() for v in tx.values() for a in v['units'].values() for x in a}
    us={x['accn'] for v in f.get('us-gaap',{}).values() for a in v['units'].values() for x in a}
    for i,a in enumerate(s['accessionNumber']):
        if s['filingDate'][i] < '2018-01-01': continue
        fm=s['form'][i]
        if fm in ('6-K','6-K/A'):
            c['6k_total']+=1
            if s['isXBRL'][i]==1:
                c['6k_xbrl']+=1
                if a in us: c['6k_xbrl_usgaap_in_cf']+=1
                else: c['6k_xbrl_usgaap0']+=1; who[k]+=1
        if fm in ('20-F','40-F','20-F/A','40-F/A') and s['isXBRL'][i]==1:
            c['fpi_annual_xbrl']+=1
            c['fpi_annual_xbrl_in_cf_any' if a in acc_any else 'fpi_annual_xbrl_absent_cf']+=1
print(dict(c)); print({k:(names[k],n) for k,n in who.items()})
