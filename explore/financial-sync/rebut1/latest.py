"""변형 술어 「최신 정기 접수번호 ∉ DB」 — 83 submissions 표본(isXBRL==1)."""
import json, collections
G='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
db=collections.defaultdict(set)
for l in open('db83.tsv'):
    t,rc,*_=l.rstrip('\n').split('\t')
    if ':' in rc: db[t].add(rc.split(':',1)[1])
gap={}
for l in open('pred-2018-01-01.tsv'):
    x=l.split('\t'); 
    if x[6]=='none': gap.setdefault(x[0],set()).add(x[5])
n=0
for l in open('t80.tsv'):
    if l.startswith('SUMMARY'): continue
    t,cik=l.rstrip('\n').split('\t')
    r=json.load(open(G+f'subs-{cik}.json'))['filings']['recent']
    c=[(r['acceptanceDateTime'][i],r['accessionNumber'][i],r['form'][i],r['filingDate'][i]) for i in range(len(r['form'])) if r['form'][i] in PER and r['isXBRL'][i]==1]
    if not c: print(t,'no periodic xbrl'); continue
    a=max(c)
    if a[1] not in db[t] and a[1] not in gap.get(t,set()):
        n+=1; print(t,a)
print('latest-not-in-db (excluding gap accn):',n)
