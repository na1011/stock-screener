"""현 편입 미국 1,000 × .factcache companyfacts: 정기 accn(us-gaap 팩트 있음 · filed 창 안) 중 DB report_code에 없는 것 — 보충과 무관하게 「영영 안 맞는」 하한."""
import json, collections, os
FC='/Users/na1011/dev/stock-screener/.factcache/'
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
db=collections.defaultdict(set)
for l in open('dbus.tsv'):
    t,rc=l.rstrip('\n').split('\t')
    if ':' in rc: db[t].add(rc.split(':',1)[1])
out=open('fc-unmatched.tsv','w'); meta=open('fc-meta.tsv','w')
for t in [x.strip() for x in open('us1000.txt')]:
    p=FC+t+'.json'
    if not os.path.exists(p): continue
    d=json.load(open(p)); f=d.get('facts',{})
    acc={}  # accn -> (form, filed, taxes, maxfiled)
    for tax,concepts in f.items():
        for c,node in concepts.items():
            for u,arr in node.get('units',{}).items():
                for x in arr:
                    a=x['accn']; e=acc.setdefault(a,[x.get('form'),x.get('filed'),set(),0])
                    e[2].add(tax); e[3]+=1
    maxfiled=max((v[1] for v in acc.values() if v[1]), default='')
    meta.write(f"{t}\t{maxfiled}\t{len(db[t])}\n")
    for a,(fm,fd,taxes,n) in acc.items():
        if fm not in PER or not fd or fd<'2018-01-01': continue
        if a in db[t]: continue
        out.write(f"{t}\t{a}\t{fm}\t{fd}\t{'/'.join(sorted(taxes))}\t{n}\n")
