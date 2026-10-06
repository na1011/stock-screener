# companyfacts 지연 단면: cache-1006(10-05 16:02~16:31 ET 수집)에서 submissions 정기공시(isXBRL=1)가 companyfacts us-gaap에 실렸는가
import json, os, glob, collections, sys
C=sys.argv[1]
FORMS={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
subs={}
for f in glob.glob(C+"/sec/submissions/CIK*.json"):
    b=os.path.basename(f)
    if "-submissions-" in b: continue
    subs[int(b[3:13])]=f
rows=[]
for cf in glob.glob(C+"/companyfacts/*.json"):
    t=os.path.basename(cf)[:-5]
    try: d=json.load(open(cf))
    except Exception as e: continue
    cik=d.get("cik")
    if cik is None or int(cik) not in subs: continue
    acc=set()
    for tax,concepts in d.get("facts",{}).items():
        if tax!="us-gaap": continue
        for c,v in concepts.items():
            for u,arr in v.get("units",{}).items():
                for x in arr: acc.add(x.get("accn"))
    s=json.load(open(subs[int(cik)]))
    r=s["filings"]["recent"]
    for i in range(len(r["accessionNumber"])):
        fd=r["filingDate"][i]
        if fd<"2026-07-01": continue
        if r["form"][i] not in FORMS: continue
        if r.get("isXBRL",[0]*len(r["form"]))[i]!=1: continue
        rows.append((t,r["form"][i],fd,r["acceptanceDateTime"][i],r["accessionNumber"][i], r["accessionNumber"][i] in acc))
by=collections.defaultdict(lambda:[0,0])
for t,f,fd,ad,a,ok in rows:
    k=fd[:7] if fd<"2026-09-21" else fd
    by[k][0]+=1; by[k][1]+= (0 if ok else 1)
print("filingDate\tfilings\tnot_in_cf_usgaap")
for k in sorted(by): print(k, by[k][0], by[k][1], sep="\t")
print("--- 09-21 이후 미수록")
for t,f,fd,ad,a,ok in sorted(rows,key=lambda x:x[3]):
    if fd>="2026-09-21" and not ok: print(t,f,fd,ad,a,sep="\t")
