import json, collections
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/'
sec=json.load(open(S+'sec-tickers.json')); fi={f:i for i,f in enumerate(sec['fields'])}
t2={}
for r in sec['data']: t2[r[fi['ticker']]]=r[fi['cik']]
def cik(s): return t2.get(s, t2.get(s.replace('.','-')))
al=json.load(open(S+'a2/out/assets-all-any.json'))
act={a['symbol']:a['name'] for a in al if a['status']=='active' and a['exchange'] in ('NYSE','NASDAQ')}
byc=collections.defaultdict(list)
for s in act:
    c=cik(s)
    if c: byc[c].append(s)
top=[l.strip() for l in open('top1000.txt')]
pool={l.split('\t')[0] for l in open('pool.tsv')}
n=0; kinds=collections.Counter(); ex=[]
for t in top:
    c=cik(t)
    if not c: continue
    sib=[s for s in byc[c] if s!=t]
    if sib:
        n+=1
        # sibling types: in pool (has shares => could itself be candidate) or not
        nonpool=[s for s in sib if s not in pool]
        if nonpool:
            kinds['has non-candidate sibling']+=1
            ex.append((t,','.join(sib),'|'.join(act[s][:30] for s in nonpool)))
print('top1000 with CIK',sum(1 for t in top if cik(t)),'with active sibling',n,kinds)
for e in ex[:60]: print('\t'.join(e))
print(len(ex))
import re
NC=re.compile(r"\bPREFERRED\b|\bPFD\b|\bWARRANTS?\b|\bRIGHTS?\b|\bDEPOSITARY\b|\bNOTES?\b|\bDEBENTURES?\b|\bBONDS?\b|\bETFS?\b|\bFUNDS?\b|\bCORPORATE UNITS?\b|\bEQUITY UNITS?\b|\bPREFERENCE\b|\bCAPITAL SECURITIES\b")
print('----')
cnt=collections.Counter(); rows=[]
for t in top:
    c=cik(t); sib=[s for s in byc[c] if s!=t]
    if not sib: continue
    nonc=[s for s in sib if NC.search(act[s].upper()) or re.search(r'\bUNITS?\b',act[s].upper())]
    common=[s for s in sib if s not in nonc]
    k='only-noncommon-sibling' if not common else 'has-common-class-sibling'
    cnt[k]+=1
    rows.append((k,t,','.join(common),','.join(nonc),'|'.join(act[s][:28] for s in nonc)))
print(cnt)
for r in sorted(rows):
    print('\t'.join(r))
