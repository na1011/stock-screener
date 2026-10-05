"""매핑 태그(SecEdgarColumn 68)만: 리더(축 없음·unitRef·가장 정밀) 대 companyfacts — companyfacts에 그 accn이 있는 AAPL·JPM·MSFT."""
import json, re, collections
from decimal import Decimal
from dup_choice import parse, G
tags={t for t in open('tags.txt').read().split()}
for cik,accn in [('320193','0000320193-26-000020'),('19617','0001628280-26-054343'),('789019','0001193125-26-323660')]:
    p=G+f'inst-{cik}-{accn.replace("-","")}.xml'
    rd={}
    for t,c,u,s,e,v,d in parse(p):
        name=('dei:'+c) if t=='dei' else c
        if name not in tags: continue
        k=(t,c,u,s,e); h=rd.get(k)
        if h is None or (d is None and h[1] is not None) or (d is not None and h[1] is not None and d>h[1]): rd[k]=(v,d)
    cf={}
    f=json.load(open(G+f'facts-{cik}.json'))['facts']
    for t in ('us-gaap','dei'):
        for c,node in f.get(t,{}).items():
            name=('dei:'+c) if t=='dei' else c
            if name not in tags: continue
            for u,arr in node['units'].items():
                for x in arr:
                    if x['accn']==accn: cf[(t,c,u,x.get('start'),x['end'])]=Decimal(str(x['val']))
    same=sum(1 for k in rd if k in cf and rd[k][0]==cf[k]); diff=[k for k in rd if k in cf and rd[k][0]!=cf[k]]
    only_r=[k for k in rd if k not in cf]; only_c=[k for k in cf if k not in rd]
    print(cik,accn,'reader',len(rd),'cf',len(cf),'same',same,'diff',len(diff),'only_reader',len(only_r),'only_cf',len(only_c))
    for k in diff+only_r[:5]+only_c[:5]: print('   ',k, rd.get(k), cf.get(k))
