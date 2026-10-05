"""창 술어 「filingDate >= fromFiscalYear-01-01」 대 SecEdgarFacts 라벨 창 「회계연도 >= fromFiscalYear」 — 83 CIK 저장 submissions(recent만)·companyfacts.
(1) 놓침: filingDate < FROM-01-01 인데 그 공시의 자기 기간(reportDate)이 회계연도 >= FROM 에 드는 정기공시(isXBRL=1) — 그중 companyfacts us-gaap 0
(2) 넘침: filingDate >= FROM-01-01 인데 자기 기간 회계연도 < FROM (라벨이 창 밖이라 출력에 안 쓰이는데 보충·실패 표면에 든다) — 그중 us-gaap 0"""
import json, collections, datetime as dt, sys
G='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
rows=[l.rstrip('\n').split('\t') for l in open('../rebut1/t80.tsv') if not l.startswith('SUMMARY')]
def fy_of(report, fye):
    if not fye: return None
    mm,dd=int(fye[:2]),int(fye[2:])
    d=dt.date.fromisoformat(report)
    for y in (d.year-1,d.year,d.year+1):
        try: end=dt.date(y,mm,dd)
        except ValueError: end=dt.date(y,mm,28)
        if end>=d - dt.timedelta(days=7):   # 52/53주 여유
            lab=(end-dt.timedelta(days=4)).year
            return lab
    return None
for FROM in (2018,2019,2025):
    cut=f'{FROM}-01-01'
    miss=collections.Counter(); over=collections.Counter(); cover=0; ex_miss=[]; ex_over=[]
    for t,cik in rows:
        s=json.load(open(G+f'subs-{cik}.json')); r=s['filings']['recent']; fye=s.get('fiscalYearEnd')
        if min(r['filingDate'])<cut: cover+=1
        f=json.load(open(G+f'facts-{cik}.json'))['facts']
        ug=collections.Counter()
        for c,node in f.get('us-gaap',{}).items():
            for u,arr in node['units'].items():
                for x in arr: ug[x['accn']]+=1
        for i,a in enumerate(r['accessionNumber']):
            fm=r['form'][i]
            if fm not in PER or r['isXBRL'][i]!=1 or not r['reportDate'][i]: continue
            lab=fy_of(r['reportDate'][i], fye)
            if lab is None: continue
            fd=r['filingDate'][i]
            if fd<cut and lab>=FROM:
                ex_miss.append((t,a,fm,fd,r["reportDate"][i],fye,ug[a])) if ug[a]>0 else None
                miss['all']+=1; 
                if ug[a]==0: miss['usgaap0']+=1; ex_miss.append((t,a,fm,fd,r['reportDate'][i],fye))
            if fd>=cut and lab<FROM:
                over['all']+=1
                if ug[a]==0: over['usgaap0']+=1; ex_over.append((t,a,fm,fd,r['reportDate'][i]))
    print(FROM,'recent이 창 시작 앞까지 닿는 CIK',cover,'/',len(rows),'놓침',dict(miss),'넘침',dict(over))
    for e in ex_miss[:12]: print('  놓침',*e)
    for e in ex_over[:12]: print('  넘침',*e)
