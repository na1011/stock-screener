import json, os, sys, collections
O=sys.argv[1]; CF=sys.argv[2]; IDX=sys.argv[3]; DAY=sys.argv[4]
FORMS={"10-K","10-K/A","10-Q","10-Q/A","20-F","20-F/A","40-F","40-F/A","6-K","6-K/A"}
mem={}
for l in open(f"{O}/us_members.tsv"):
    sid,t=l.rstrip("\n").split("\t"); mem[int(sid)]=t
t2cik={}; miss=[]
for sid,t in mem.items():
    p=f"{CF}/{t}.json"
    if not os.path.exists(p): miss.append(t); continue
    try:
        with open(p) as f: head=f.read(200)
        import re
        m=re.search(r'"cik"\s*:\s*(\d+)',head)
        if m: t2cik[t]=int(m.group(1))
        else:
            d=json.load(open(p)); t2cik[t]=int(d["cik"])
    except Exception as e: miss.append(t)
print("members",len(mem),"cik mapped",len(t2cik),"no cache",len(miss), miss[:20])
cik2t=collections.defaultdict(list)
for t,c in t2cik.items(): cik2t[c].append(t)
rows=[]
started=False
for l in open(IDX,encoding="latin-1"):
    if l.startswith("-----"): started=True; continue
    if not started: continue
    p=l.rstrip("\n").split("|")
    if len(p)<5: continue
    rows.append(p)
forms=collections.Counter(); hits=collections.defaultdict(list); datefiled=collections.Counter()
for cik,name,form,df,fn in rows:
    c=int(cik)
    if c in cik2t and form in FORMS:
        for t in cik2t[c]: hits[t].append((form,df,fn.split("/")[-1][:-4]))
        forms[form]+=1
    if form in FORMS: datefiled[df]+=1
print("index periodic rows datefiled", dict(datefiled))
print("member hits", len(hits), dict(forms))
db=collections.defaultdict(set)
for l in open(f"{O}/us_db_tmp.tsv"):
    sid,pub,rc,fy,fp=l.rstrip("\n").split("\t")
    if pub==DAY: db[mem[int(sid)]].add((rc,fy,fp))
print("db stocks pub=",DAY,len(db))
A=set(hits); B=set(db)
print("index&db",len(A&B),"index-only",len(A-B),"db-only",len(B-A))
for t in sorted(A-B): print("  IDX-ONLY",t,hits[t])
for t in sorted(B-A): print("  DB-ONLY",t,sorted(db[t]), "cik", t2cik.get(t))
