# 0콜 · .factcache(companyfacts 09-15~27) · 현 편입 미국. 공시(accn · 10-K/10-Q 원본)마다 companyfacts의 (fy, fp)를 읽어
# 첫 공시 ~ 마지막 공시 사이 회계연도 2018~2025에서 {Q1,Q2,Q3,FY} 중 빠진 것을 센다(= companyfacts에 그 접수번호의 팩트가 0인 정기공시 후보).
import json, collections
summ=collections.Counter(); rows=[]
for t in open('cur_us.txt').read().split():
    try: d=json.load(open(f'/Users/na1011/dev/stock-screener/.factcache/{t}.json'))
    except FileNotFoundError: summ['nocache']+=1; continue
    seen=collections.defaultdict(set); accs=set()
    for tag,node in d['facts'].get('us-gaap',{}).items():
        for unit,arr in node.get('units',{}).items():
            for f in arr:
                if f.get('form') not in ('10-K','10-Q'): continue
                if f.get('fy') is None or f.get('fp') is None: continue
                if f['accn'] in accs: continue
                accs.add(f['accn']); seen[int(f['fy'])].add(f['fp'])
    if not seen or not any('Q1' in s or 'Q2' in s or 'Q3' in s for s in seen.values()): summ['no10q']+=1; continue
    fys=sorted(seen); lo,hi=fys[0],fys[-1]
    miss=[]
    for fy in range(max(lo,2018), min(hi,2026)+1):
        exp={'Q1','Q2','Q3','FY'}
        if fy==lo: # 첫 해: 처음 본 기 앞은 상장 전
            order=['Q1','Q2','Q3','FY']; first=min(order.index(p) for p in seen[fy] if p in order) if seen[fy]&set(order) else 4
            exp=set(order[first:])
        if fy==hi: # 마지막 해: 마지막 본 기 뒤는 아직 안 옴(TAIL — (가) 본 몫)
            order=['Q1','Q2','Q3','FY']; last=max(order.index(p) for p in seen[fy] if p in order) if seen[fy]&set(order) else -1
            exp&=set(order[:last+1])
        m=sorted(exp-seen[fy])
        for p in m: miss.append(f'{fy}{p}')
    summ['scanned']+=1
    if miss: summ['tickers_with_hole']+=1; summ['holes']+=len(miss); rows.append((t,miss))
open('holes2.tsv','w').write(''.join(f'{t}\t{" ".join(m)}\n' for t,m in rows))
byyear=collections.Counter(h[:4] for t,m in rows for h in m)
print(dict(summ)); print(sorted(byyear.items()))
