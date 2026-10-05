import json, collections
from decimal import Decimal
import xml.etree.ElementTree as ET
O='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
exec(open('dup.py').read().split('for name in')[0])
facts=raw(O+'inst-1800-000162828026050134.xml')
def dec(d): return 10**9 if d=='INF' else (int(d) if d is not None else -10**9)
best={}
for t,l,p,u,d,v,i in facts:
    if t not in ("us-gaap","dei") or u is None or v is None or not v.strip(): continue
    k=(t,l,p[0],p[1])
    if k not in best or dec(d)>dec(best[k][0]): best[k]=(d,Decimal(v.strip()))
cf=json.load(open(O+'facts-1800.json'))
# values per (tax,concept,start,end) in cf (any accn) 
cv=collections.defaultdict(set)
for tax,cs in cf['facts'].items():
    for c,b in cs.items():
        for u,arr in b['units'].items():
            for n in arr: cv[(tax,c,n.get('start'),n['end'])].add(Decimal(str(n['val'])))
same=diff=new=0; dl=[]
for k,(d,v) in best.items():
    if k not in cv: new+=1
    elif v in cv[k]: same+=1
    else: diff+=1; dl.append((k,v,cv[k]))
print('dedup keys',len(best),'same',same,'diff',diff,'new',new)
for x in dl: print(x)
