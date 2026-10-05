"""8 인스턴스: 축 없는 (택소노미·개념·단위·기간) 중복 — 값이 다른 것 · 정밀도 · companyfacts가 실은 쪽(그 accn이 companyfacts에 있는 AAPL·JPM·MSFT만)."""
import xml.etree.ElementTree as ET, json, glob, collections, re
from decimal import Decimal
G='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
XI='{http://www.xbrl.org/2003/instance}'
def parse(path):
    root=ET.parse(path).getroot()
    ctx={}
    for c in root.findall(XI+'context'):
        seg=c.find(XI+'entity/'+XI+'segment'); dims=len(list(seg)) if seg is not None else 0
        ident=c.find(XI+'entity/'+XI+'identifier').text.strip()
        per=c.find(XI+'period'); i=per.find(XI+'instant')
        ctx[c.get('id')]=((None,i.text.strip()) if i is not None else (per.find(XI+'startDate').text.strip(),per.find(XI+'endDate').text.strip()),dims,ident)
    units={}
    for u in root.findall(XI+'unit'):
        ms=[m.text.split(':')[-1] for m in u.iter(XI+'measure')]
        d=u.find('.//'+XI+'unitDenominator')
        if d is not None:
            num=[m.text.split(':')[-1] for m in u.find('.//'+XI+'unitNumerator').iter(XI+'measure')]
            den=[m.text.split(':')[-1] for m in d.iter(XI+'measure')]
            units[u.get('id')]='*'.join(num)+'/'+'*'.join(den)
        else: units[u.get('id')]='*'.join(ms)
    out=[]
    for el in root:
        if not el.tag.startswith('{') or el.get('contextRef') is None or el.get('unitRef') is None: continue
        uri,local=el.tag[1:].split('}')
        tax='us-gaap' if uri.startswith('http://fasb.org/us-gaap/') else ('dei' if uri.startswith('http://xbrl.sec.gov/dei/') else None)
        if tax is None: continue
        if el.get('{http://www.w3.org/2001/XMLSchema-instance}nil')=='true' or el.text is None: continue
        (s,e),dims,ident=ctx[el.get('contextRef')]
        if dims: continue
        dec=el.get('decimals'); dec=None if dec in (None,'INF') else int(dec)
        out.append((tax,local,units[el.get('unitRef')],s,e,Decimal(el.text.strip()),dec))
    return out
tot=collections.Counter()
for p in sorted(glob.glob(G+'inst-*.xml')):
    m=re.match(r'.*inst-(\d+)-(\d{10})(\d\d)(\d{6})\.xml',p); cik=m.group(1); accn=f'{m.group(2)}-{m.group(3)}-{m.group(4)}'
    facts=parse(p)
    by=collections.defaultdict(list)
    for t,c,u,s,e,v,d in facts: by[(t,c,u,s,e)].append((v,d))
    cf=json.load(open(G+f'facts-{cik}.json'))['facts']
    incf=any(x['accn']==accn for tx in cf.values() for cn in tx.values() for arr in cn['units'].values() for x in arr)
    dupdiff=[(k,vs) for k,vs in by.items() if len({v for v,_ in vs})>1]
    samedec=[(k,vs) for k,vs in dupdiff if any(d1==d2 and v1!=v2 for v1,d1 in vs for v2,d2 in vs)]
    print(f'{cik} {accn} incf={incf} keys={len(by)} dupkeys={sum(1 for v in by.values() if len(v)>1)} dupdiff={len(dupdiff)} samedec_diff={len(samedec)}')
    for k,vs in dupdiff:
        t,c,u,s,e=k
        cfv=None
        if incf:
            arr=cf.get(t,{}).get(c,{}).get('units',{}).get(u,[])
            cfv=[Decimal(str(x['val'])) for x in arr if x['accn']==accn and x.get('start')==s and x['end']==e]
        best=max(vs,key=lambda x:(1e9 if x[1] is None else x[1]))[0]
        print('   ',c,u,s,e,'inst=',[(str(v),d) for v,d in vs],'most_precise=',best,'cf=',cfv)
