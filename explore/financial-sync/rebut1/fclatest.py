"""변형 술어 「최신 정기 접수번호 ∉ DB」 — .factcache companyfacts 998종목(companyfacts에 있는 공시만)."""
import json, collections, os
FC='/Users/na1011/dev/stock-screener/.factcache/'
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
db=collections.defaultdict(set)
for l in open('dbus.tsv'):
    t,rc=l.rstrip('\n').split('\t')
    if ':' in rc: db[t].add(rc.split(':',1)[1])
cnt=collections.Counter(); ex=[]
for t in [x.strip() for x in open('us1000.txt')]:
    p=FC+t+'.json'
    if not os.path.exists(p): continue
    f=json.load(open(p)).get('facts',{})
    acc={}
    for tax,cs in f.items():
        for c,node in cs.items():
            for u,arr in node.get('units',{}).items():
                for x in arr:
                    if x.get('form') in PER and x.get('filed'):
                        e=acc.setdefault(x['accn'],[x['filed'],x['form'],set()]); e[2].add(tax)
    if not acc: cnt['noperiodic']+=1; continue
    a=max(acc.items(), key=lambda kv:(kv[1][0],kv[0]))
    if a[0] in db[t]: cnt['match']+=1
    else:
        cls='us-gaap' if 'us-gaap' in a[1][2] else '/'.join(sorted(a[1][2]))
        cnt['miss:'+a[1][1]+':'+cls]+=1; ex.append((t,a[0],a[1][0],a[1][1],cls))
print(dict(cnt))
for e in ex: print(*e, sep='\t')
