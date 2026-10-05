# 0콜: .factcache(companyfacts 09-15~27 스냅숏)에서 현 편입 미국 종목의 정기공시(10-K·10-Q 원본) 순서에 빈 분기가 있는지 센다.
# 공시마다 "기말" = 그 accn이 실은 팩트의 최대 end. 연속 기말 간격이 120일 넘으면 그 사이 공시 하나 이상이 companyfacts에 없다(후보).
# 창: 기말 2018-01-01 이후(정기 FR 창 8년 · 2018~2026). 10-Q를 한 번도 안 낸 종목(20-F·40-F 발행사)은 뺀다.
import json, sys, datetime as dt, collections
D=lambda s: dt.date.fromisoformat(s)
out=open('holes.tsv','w'); summ=collections.Counter(); hol_t=[]
for t in open('cur_us.txt').read().split():
    p=f'/Users/na1011/dev/stock-screener/.factcache/{t}.json'
    try: d=json.load(open(p))
    except FileNotFoundError: summ['nocache']+=1; continue
    acc={}  # accn -> (form, max_end, filed)
    for ns in ('us-gaap',):
        for tag,node in d['facts'].get(ns,{}).items():
            for unit,arr in node.get('units',{}).items():
                for f in arr:
                    form=f.get('form','')
                    if form not in ('10-K','10-Q'): continue
                    a=f['accn']; e=f['end']
                    cur=acc.get(a)
                    if cur is None or e>cur[1]: acc[a]=(form,e,f['filed'])
    if not any(v[0]=='10-Q' for v in acc.values()): summ['no10q']+=1; continue
    ends=sorted({v[1] for v in acc.values() if v[1]>='2017-10-01'})
    gaps=[(a,b,(D(b)-D(a)).days) for a,b in zip(ends,ends[1:]) if (D(b)-D(a)).days>120]
    gaps=[g for g in gaps if g[1]>='2018-01-01']
    maxend=ends[-1] if ends else ''
    if gaps:
        summ['with_gap']+=1; hol_t.append(t)
        for a,b,n in gaps: out.write(f'{t}\t{a}\t{b}\t{n}\n'); summ['gaps']+=1
    summ['scanned']+=1
print(dict(summ)); print(' '.join(hol_t))
