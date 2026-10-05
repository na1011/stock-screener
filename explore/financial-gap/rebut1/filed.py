import json, glob, collections
O='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
PER={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
stat=collections.Counter(); ex=[]
for sf in sorted(glob.glob(O+'subs-*.json')):
    cik=sf.split('-')[-1][:-5]
    r=json.load(open(sf))['filings']['recent']
    sub={a:(r['filingDate'][i],r['form'][i],r['reportDate'][i]) for i,a in enumerate(r['accessionNumber'])}
    cf=json.load(open(O+f'facts-{cik}.json'))
    acc={}
    for tax,cs in cf['facts'].items():
        for c,b in cs.items():
            for u,arr in b['units'].items():
                for n in arr:
                    if n['form'] in PER: acc.setdefault(n['accn'],set()).add((n['filed'],n['form']))
    for a,v in acc.items():
        if a not in sub: stat['not in recent']+=1; continue
        if len(v)>1: stat['multi filed/form in cf']+=1
        fd,fm,rd=sub[a]
        for filed,form in v:
            stat[('filed eq' if filed==fd else 'filed NE', 'form eq' if form==fm else 'form NE')]+=1
            if (filed!=fd or form!=fm) and len(ex)<10: ex.append((cik,a,filed,form,fd,fm))
print(stat); 
for e in ex: print(e)
