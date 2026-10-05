# 0콜: 83 CIK 캐시(ga/out2 · subs recent만 · 페이지 미포함)에서 보충 대상(form ∈ 보충 집합 ∧ isXBRL=1 ∧ companyfacts us-gaap에 그 accn 0) 폼 분포
import glob, json, os, re, collections
GA='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2'
FORMS={'10-K','10-Q','10-K/A','10-Q/A','20-F','20-F/A','40-F','40-F/A','6-K','6-K/A','10-KT','10-QT','10-KT/A','10-QT/A'}
cnt=collections.Counter(); per=collections.defaultdict(list); n=0
for sp in sorted(glob.glob(f'{GA}/subs-*.json')):
    cik=re.search(r'subs-(\d+)',sp).group(1); fp=f'{GA}/facts-{cik}.json'
    if not os.path.exists(fp): continue
    n+=1
    s=json.load(open(sp)); f=json.load(open(fp))
    acc=set()
    for c,units in f.get('facts',{}).get('us-gaap',{}).items():
        for u,arr in units.get('units',{}).items():
            for x in arr: acc.add(x['accn'])
    r=s['filings']['recent']
    for a,fm,fd,x in zip(r['accessionNumber'],r['form'],r['filingDate'],r['isXBRL']):
        if fm in FORMS and x==1 and fd>='2018-01-01' and a not in acc:
            cnt[fm]+=1; per[fm].append((s.get('tickers',[''])[0] if s.get('tickers') else cik,a,fd))
print('CIK',n); print(dict(cnt))
for fm in sorted(per):
    if fm not in ('10-Q',): print(fm, per[fm][:12])
