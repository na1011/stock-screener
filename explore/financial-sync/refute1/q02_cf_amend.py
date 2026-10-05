# 전체 현 편입 미국: companyfacts(.factcache, 09-15~09-27) 정기공시 접수번호(fy>=2018) 중 DB report_code에 없는 것
import json, collections, os
C='/Users/na1011/dev/stock-screener/.factcache/'
P={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
db=collections.defaultdict(set)
for l in open('db_accn.tsv'):
    t,a=l.rstrip('\n').split('\t'); db[t].add(a)
cur=[l.strip() for l in open('cur_us.txt') if l.strip()]
have=0; nofile=[]; hit={}; formc=collections.Counter()
for t in cur:
    fn=C+t+'.json'
    if not os.path.exists(fn): nofile.append(t); continue
    have+=1
    d=json.load(open(fn)); acc={}
    for ns in ('us-gaap','ifrs-full'):
        for k,v in d.get('facts',{}).get(ns,{}).items():
            for u,arr in v['units'].items():
                for x in arr:
                    if x.get('form') in P and (x.get('fy') or 0)>=2018 and x['end']>='2018-01-01':
                        acc.setdefault(x['accn'],(x['form'],x['filed']))
    m=[(a,f,fd) for a,(f,fd) in acc.items() if a not in db[t]]
    if m:
        hit[t]=m
        for a,f,fd in m: formc[f]+=1
print('current',len(cur),'with cache',have,'nofile',len(nofile))
print('tickers with periodic accn (fy>=2018) in companyfacts but not in DB:',len(hit))
print('by form',dict(formc))
import itertools
for t,m in itertools.islice(sorted(hit.items()),0,40): print(t,len(m),sorted(m,key=lambda x:x[2])[-3:])
open('q02_hits.tsv','w').write(''.join(f'{t}\t{a}\t{f}\t{fd}\n' for t,m in hit.items() for a,f,fd in m))
