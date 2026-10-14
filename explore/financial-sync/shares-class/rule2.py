import json,os,re,collections,sys
import xml.etree.ElementTree as ET
XI='http://www.xbrl.org/2003/instance'; XD='http://xbrl.org/2006/xbrldi'; AX='us-gaap:StatementClassOfStockAxis'
WTAGS=('WeightedAverageNumberOfSharesOutstandingBasic','WeightedAverageNumberOfShareOutstandingBasicAndDiluted','WeightedAverageNumberOfSharesIssuedBasic','WeightedAverageLimitedPartnershipUnitsOutstanding')
ETAGS=('EarningsPerShareBasic','EarningsPerShareBasicAndDiluted','IncomeLossFromContinuingOperationsPerBasicShare')
NTAGS=('NetIncomeLossAvailableToCommonStockholdersBasic','NetIncomeLoss','ProfitLoss')
nk=lambda s: re.sub(r'[^A-Z0-9]','',(s or '').upper())
def load(p):
    r=ET.parse(p).getroot(); ctx={}
    for c in r.iter('{%s}context'%XI):
        d={e.get('dimension'):e.text for e in c.iter('{%s}explicitMember'%XD)}
        per=c.find('{%s}period'%XI); ctx[c.get('id')]=(d,tuple(y.text for y in per))
    F=collections.defaultdict(dict)  # tag -> (member, period) -> value
    cover={}
    for e in r:
        t=e.tag.split('}')[-1]; cx=ctx.get(e.get('contextRef'))
        if cx is None or not (e.text or '').strip(): continue
        d,per=cx
        if set(d)-{AX}: continue
        m=d.get(AX,'-')
        if t in ('EntityCommonStockSharesOutstanding','TradingSymbol','Security12bTitle'):
            cover.setdefault(t,{})[m]=e.text.strip(); continue
        if t in WTAGS+ETAGS+NTAGS and len(per)==2:
            try: F[t][(m,per)]=float(e.text)
            except: pass
    return F,cover
def lpmember(ticker,cover,members):
    sym=cover.get('TradingSymbol',{}); ttl=cover.get('Security12bTitle',{}); sh=cover.get('EntityCommonStockSharesOutstanding',{})
    hits=[m for m,s in sym.items() if m!='-' and nk(ticker) in [nk(x) for x in re.split(r'[,\s/]+',s)]]
    title=None
    if hits:
        if len(hits)==1:
            m=hits[0]
            if m in members: return m,'direct'
            title=ttl.get(m)
            L=re.search(r'Class([A-Z]\d?)',m.split(':')[-1])
            if L: 
                c=[k for k in members if re.search(r'Class'+L.group(1)+r'(?![a-z0-9])',k.split(':')[-1]) or re.search(r'Class'+L.group(1)+r'(Member|Common|NonVoting|Ordinary|Shares|Stock)',k.split(':')[-1])]
                if len(c)==1: return c[0],'direct-letter'
    elif nk(ticker) in [nk(x) for x in re.split(r'[,\s/]+',sym.get('-',''))]:
        title=ttl.get('-')
    if title:
        m=re.search(r'Class\s+([A-Z])(?:-?(\d))?\b',title)
        if m:
            L=m.group(1)+(m.group(2) or '')
            c=[k for k in members if re.search(r'Class'+L+r'(?![a-z0-9])',k.split(':')[-1]) or re.search(r'Class'+L+r'(Member|Common|NonVoting|Ordinary|Shares|Stock)',k.split(':')[-1])]
            if len(c)==1: return c[0],'title'
        elif re.search(r'common',title,re.I):
            c=[k for k in members if k.split(':')[-1] in('CommonStockMember',)]
            if len(c)==1: return c[0],'title-common'
    return None,'unmapped'
def closes(n,e,num):
    if not e or num is None: return None
    tol=0.005/abs(e)+0.005
    return abs(n*e/num-1)<=tol if num else None
def rule(ticker,F,cover,form):
    # own period: latest end; prefer 3m for 10-Q, 12m for 10-K
    pers=set(p for t in WTAGS for (m,p) in F[t])
    if not pers: return None,'no-wavg',None
    import datetime as dt
    def span(p): return (dt.date.fromisoformat(p[1])-dt.date.fromisoformat(p[0])).days
    want=(80,100) if form.startswith('10-Q') else (350,380)
    cand=[p for p in pers if want[0]<span(p)<want[1]]
    if not cand: return None,'no-own-period',None
    P=max(cand,key=lambda p:p[1])
    w={};e={};num=None
    for t in WTAGS:
        for (m,p),v in F[t].items():
            if p==P and m not in w: w[m]=v
    for t in ETAGS:
        for (m,p),v in F[t].items():
            if p==P and m not in e: e[m]=v
    for t in NTAGS:
        v=F[t].get(('-',P))
        if v is not None: num=v; break
    # per-class allocated income, when the ledger states it for every EPS member
    edm=[m for m in e if m!='-']
    alloc=None
    for t in NTAGS[:2]:
        if edm and all((m,P) in F[t] for m in edm): alloc=sum(F[t][(m,P)] for m in edm); break
    edim=[m for m in e if m!='-']
    # case A0: non-dim w, EPS only on one member
    if '-' in w and '-' not in e and len(edim)==1:
        n=w['-']; ok=closes(n,e[edim[0]],num)
        return (n,'A0-ok',P) if ok else (None,'A0-FAIL',P) if ok is False else (n,'A0-unverifiable',P)
    # case M: member-only w, non-dim EPS
    if '-' not in w and '-' in e and not edim and w:
        n=sum(w.values()); ok=closes(n,e['-'],num)
        return (n,'M-ok',P) if ok else (None,'M-FAIL',P) if ok is False else (n,'M-unverifiable',P)
    # case A: non-dim w
    if '-' in w and ('-' in e or not edim):
        n=w['-']; ok=closes(n,e.get('-'),num)
        return (n if ok is not False else None),('A-ok' if ok else ('A-unverifiable' if ok is None else 'A-FAIL')),P
    dims=[m for m in w if m!='-']
    eds={m:e[m] for m in dims if m in e}
    if dims and eds:
        vals=set(round(v,4) for v in eds.values())
        if len(vals)==1:
            n=sum(w[m] for m in eds); ok=closes(n,list(eds.values())[0],alloc if alloc is not None else num)
            if ok: return n,'B-equal-ok',P
        Lm,how=lpmember(ticker,cover,list(eds))
        if Lm and eds[Lm]:
            n=sum(w[m]*eds[m] for m in eds)/eds[Lm]; ok=closes(n,eds[Lm],alloc if alloc is not None else num)
            if ok: return n,'B-sum-ok/'+how,P
            n2=w[Lm]; ok2=closes(n2,eds[Lm],num)
            if ok2: return n2,'B-member-ok/'+how,P
            return None,'B-FAIL/'+how,P
        if '-' in w:
            n=w['-']; ok=closes(n,e.get('-'),num)
            if ok: return n,'A2-ok',P
        return None,'B-unmapped',P
    if '-' not in w and not e and w:
        return sum(w.values()),'M0-unverifiable',P
    if '-' in w:
        n=w['-']; ok=closes(n,e.get('-'),num)
        return (n if ok is not False else None),('A3-ok' if ok else ('A3-unverifiable' if ok is None else 'A3-FAIL')),P
    return None,'no-rule',P
if __name__=='__main__':
    o=json.load(open('cmp.json')); meta={}
    for f in ('probe2.json','probe3.json','probe4.json'):
        for x in json.load(open(f)):
            if 'form' in x: meta[x['cik']]=x['form']
    res=collections.Counter(); bad=[]
    for x in o:
        if not x['member']: continue
        p=next(f'{d}/{x["cik"]}.xml' for d in('inst','inst3','inst4') if os.path.exists(f'{d}/{x["cik"]}.xml'))
        F,cover=load(p)
        n,k,P=rule(x['t'],F,cover,meta.get(x['cik'],'10-Q'))
        res[k.split('/')[0]]+=1
        if n is None or 'FAIL' in k or 'unverif' in k: bad.append((x['t'],k,P))
    print(res); print(bad)
