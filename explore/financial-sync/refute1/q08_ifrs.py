# 현 편입 미국 중 companyfacts에 ifrs-full 팩트가 있는 종목 — 그 접수번호(정기 · fy>=2018) 중 us-gaap 팩트 0인 것 = 보충 술어(isXBRL=1 ∧ us-gaap 0)가 매 FR 집을 공시
import json, os, collections
C='/Users/na1011/dev/stock-screener/.factcache/'
P={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
cur=[l.strip() for l in open('cur_us.txt') if l.strip()]
out=[]
for t in cur:
    fn=C+t+'.json'
    if not os.path.exists(fn): continue
    d=json.load(open(fn)); f=d.get('facts',{})
    if 'ifrs-full' not in f: continue
    def accs(ns):
        s={}
        for v in f.get(ns,{}).values():
            for arr in v['units'].values():
                for x in arr:
                    if x.get('form') in P and (x.get('fy') or 0)>=2018: s[x['accn']]=x['form']
        return s
    i=accs('ifrs-full'); u=accs('us-gaap')
    only=[a for a in i if a not in u]
    out.append((t,len(i),len(u),len(only),collections.Counter(i[a] for a in only)))
print('current members with ifrs-full facts:',len(out),' total periodic accn with ifrs only:',sum(o[3] for o in out))
for o in out: print(*o)
