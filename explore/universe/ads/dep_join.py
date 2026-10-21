import sys,json,re
S=sys.argv[1]; inc=json.load(open(f'{S}/inc.json')); a=json.load(open(f'{S}/alp.json')); ads=set(json.load(open(f'{S}/ads_ciks.json')))
W={'one':1,'two':2,'three':3,'four':4,'five':5,'six':6,'seven':7,'eight':8,'nine':9,'ten':10,'eleven':11,'twelve':12,'fifteen':15,'sixteen':16,'twenty':20,'thirty':30,'forty':40,'fifty':50,'sixty':60,'eighty':80,'ninety':90,'hundred':100}
def ratio(rec):
    for t in [x[1] for x in rec.get('title',[])]:
        s=re.sub(r'\s+',' ',t.replace('\xa0',' ')).lower()
        m=re.search(r'each (?:ten|\d+) \(?\d*\)? ?(?:american depositary shares|adss) represent\w* ([\w\-]+) \(?(\d+)?\)?',s)
        m2=re.search(r'(?:each|one) (\w+ )?\(?(\d+)?\)? ?(?:american depositary shares?|adss?) represent\w* (?:one|1) \(1\)',s)
        if m2 and m2.group(1): 
            n=W.get(m2.group(1).strip()) or (int(m2.group(2)) if m2.group(2) else None)
            if n: return 1/n,'title-inv'
        m=re.search(r'represent\w*\s*\(?([\d.,]+|[a-z\-]+)\)?\s*\(?(\d+)?\)?\s*(?:class [a-z] )?(?:ordinary|common)',s)
        if m:
            w=m.group(1).replace(',','')
            try: return float(w),'title'
            except: pass
            if w in W: return float(W[w]),'title'
            if '-' in w:
                p=w.split('-'); 
                if all(x in W for x in p): return float(sum(W[x] for x in p)),'title'
    r=rec.get('ratio')
    if r:
        try: return float(r[0][1]),'tag'
        except: pass
    return None,'none'
cand=[l.rstrip('\n').split('\t') for l in open(f'{S}/cand.tsv')]
miss=[r[0] for r in cand if r[1] in ads and r[0] not in a['close']]
json.dump(miss,open(f'{S}/miss.json','w'))
out=[]
for r in cand:
    t,cik,sh,bs,bm,rc,mem,name=r
    if cik not in ads: continue
    rec=inc[cik]; k,src=ratio(rec)
    nm=a['names'].get(t) or ''
    cut=bool(re.search(r'\b(PREFERRED|PFD|WARRANTS?|RIGHTS?|DEPOSITARY|NOTES?|DEBENTURES?|BONDS?|ETFS?|FUNDS?)\b',nm.upper())) if nm else None
    syms=[x[1] for x in rec.get('sym',[])]
    out.append(dict(t=t,cik=cik,sh=float(sh),bs=bs,bm=bm,rc=rc,mem=mem=='t',name=name,alp=nm,cut=cut,k=k,src=src,syms=syms,form=rec.get('form'),titles=[x[1][:80] for x in rec.get('title',[])]))
json.dump(out,open(f'{S}/join.json','w'),indent=1)
print(len(out),'rows; missing closes',len(miss))
