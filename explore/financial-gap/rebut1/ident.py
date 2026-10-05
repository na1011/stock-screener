import sys, json, collections
sys.path.insert(0,'..')
from xbrl_inst import parse
from decimal import Decimal
O='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
cik,acc,f='320193','0000320193-26-000020','inst-320193-000032019326000020.xml'
cf=json.load(open(O+f'facts-{cik}.json'))
c=set(); ctax=collections.Counter()
for tax,cs in cf['facts'].items():
    for k,b in cs.items():
        for u,arr in b['units'].items():
            for n in arr:
                if n['accn']==acc: c.add((tax,k,u,n.get('start'),n['end'],Decimal(str(n['val'])))); ctax[tax]+=1
inst=set((t,l,u,s,e,v) for t,l,u,s,e,v in parse(O+f) if u is not None)
print('cf nodes for accn',len(c),ctax)
print('inst distinct (with unit)',len(inst))
print('cf - inst',len(c-inst)); [print('  ',x) for x in list(c-inst)[:10]]
d=inst-c
print('inst - cf',len(d),collections.Counter(x[0] for x in d))
for x in sorted(d):
    if x[0] in ('us-gaap','dei'): print('  ',x)
