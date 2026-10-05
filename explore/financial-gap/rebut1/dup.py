import sys, json, collections
sys.path.insert(0,'..')
import xml.etree.ElementTree as ET
from decimal import Decimal
XBRLI='{http://www.xbrl.org/2003/instance}'
O='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
def raw(path):
    root=ET.parse(path).getroot()
    ns={}
    for ev,(p,u) in ET.iterparse(path, events=['start-ns']): ns[u]=p
    ctx={}
    for c in root.findall(XBRLI+'context'):
        per=c.find(XBRLI+'period'); seg=c.find(XBRLI+'entity/'+XBRLI+'segment')
        dims=len(list(seg)) if seg is not None else 0
        inst=per.find(XBRLI+'instant')
        ctx[c.get('id')]=((None,inst.text.strip()) if inst is not None else (per.find(XBRLI+'startDate').text.strip(),per.find(XBRLI+'endDate').text.strip()),dims)
    out=[]
    for el in root:
        if not el.tag.startswith('{') or el.get('contextRef') is None: continue
        uri,local=el.tag[1:].split('}')
        c=ctx.get(el.get('contextRef'))
        if c is None or c[1]>0: continue
        out.append((ns.get(uri),local,c[0],el.get('unitRef'),el.get('decimals'),el.text, el.get('id')))
    return out
for name in ['inst-320193-000032019326000020.xml','inst-1800-000162828026050134.xml']:
    facts=raw(O+name)
    g=collections.defaultdict(list)
    for t,l,p,u,d,v,i in facts:
        g[(t,l,p,u)].append((d,v))
    dups={k:v for k,v in g.items() if len(v)>1}
    incons={k:v for k,v in dups.items() if len(set(Decimal(x[1].strip()) for x in v if x[1] and x[1].strip()))>1}
    print(name,'facts',len(facts),'keys',len(g),'dupkeys',len(dups),'inconsistent',len(incons))
    for k,v in list(incons.items())[:30]: print('  ',k,v)
