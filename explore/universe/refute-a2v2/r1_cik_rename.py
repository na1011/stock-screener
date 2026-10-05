# 계획 v2의 「같은 CIK의 다른 티커가 Alpaca 활성 목록에 있으면 개명 · 제외」가 숨은 43 중 누구를 빼는가
# 활성 CIK 집합 = Alpaca 활성 NYSE/NASDAQ 심볼마다 findCik(심볼 그대로 → '.'→'-')
import json,csv,sys
base='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/'
sec=json.load(open(base+'sec-tickers.json'))
f=sec['fields']; ti=f.index('ticker'); ci=f.index('cik'); ei=f.index('exchange'); ni=f.index('name')
bytick={}; bycik={}
for r in sec['data']:
    bytick[r[ti]]=(r[ci],r[ei],r[ni])
    bycik.setdefault(r[ci],[]).append((r[ti],r[ei]))
def findcik(s):
    if s in bytick: return bytick[s][0]
    s2=s.replace('.','-')
    return bytick[s2][0] if s2 in bytick else None
assets=json.load(open(base+'a2/out/assets-all-any.json'))
active=[a for a in assets if a['status']=='active' and a['exchange'] in ('NYSE','NASDAQ')]
print('active NYSE/NASDAQ', len(active))
activeSyms={a['symbol'] for a in active}
activeCik={}
for a in active:
    c=findcik(a['symbol'])
    if c is not None: activeCik.setdefault(c,[]).append(a['symbol'])
hid=list(csv.DictReader(open(base+'wt-explore/explore/universe/rebut-a2/hidden43.tsv'),delimiter='\t'))
for h in hid:
    t=h['ticker']; c=findcik(t)
    others=[x for x in activeCik.get(c,[]) if x!=t] if c else []
    sectick=[x for x in bycik.get(c,[])] if c else []
    print(t, c, h['cap_usd_m'], h['session_1002'], 'ACTIVE_SAME_CIK=' + ','.join(others) if others else '-', 'sec=', sectick)
