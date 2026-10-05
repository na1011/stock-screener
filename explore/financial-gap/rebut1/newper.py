import sys, json, collections
sys.path.insert(0,'..')
from xbrl_inst import parse
O='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
def cfkeys(cik):
    d=json.load(open(O+f'facts-{cik}.json'))
    ks=set()
    for tax in ('us-gaap','dei'):
        for c,body in d['facts'].get(tax,{}).items():
            for u,arr in body['units'].items():
                for n in arr: ks.add((tax,c,n.get('start'),n['end']))
    return ks,d
for cik,f in [('1800','inst-1800-000162828026050134.xml')]:
    facts=parse(O+f)
    ks,d=cfkeys(cik)
    new=[x for x in facts if x[0] in('us-gaap','dei') and (x[0],x[1],x[3],x[4]) not in ks]
    old=[x for x in facts if x[0] in('us-gaap','dei') and (x[0],x[1],x[3],x[4]) in ks]
    print('us-gaap/dei facts',len([x for x in facts if x[0] in('us-gaap','dei')]),'new',len(new),'old',len(old))
    print(collections.Counter((x[3],x[4]) for x in new).most_common())
    print('distinct new keys', len(set((x[0],x[1],x[3],x[4]) for x in new)))
    print('taxonomies all', collections.Counter(x[0] for x in facts))
