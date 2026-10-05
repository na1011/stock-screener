"""XBRL 인스턴스(iXBRL에서 뽑힌 _htm.xml)를 읽어 축 없는 팩트를 companyfacts 모양으로 낸다 — (가) 동일성 탐침용 원형."""
import xml.etree.ElementTree as ET, re
from decimal import Decimal
XBRLI='{http://www.xbrl.org/2003/instance}'
XBRLDI='{http://xbrl.org/2006/xbrldi}'
def parse(path):
    root=ET.parse(path).getroot()
    ns={}
    for ev,(p,u) in ET.iterparse(path, events=['start-ns']):
        ns[u]=p
    ctx={}
    for c in root.findall(XBRLI+'context'):
        cid=c.get('id'); per=c.find(XBRLI+'period'); seg=c.find(XBRLI+'entity/'+XBRLI+'segment')
        dims = len(list(seg)) if seg is not None else 0
        inst=per.find(XBRLI+'instant')
        if inst is not None: ctx[cid]=(None, inst.text.strip(), dims)
        else: ctx[cid]=(per.find(XBRLI+'startDate').text.strip(), per.find(XBRLI+'endDate').text.strip(), dims)
    units={}
    for u in root.findall(XBRLI+'unit'):
        m=u.find(XBRLI+'measure')
        if m is not None: units[u.get('id')]=m.text.split(':')[-1]
        else:
            num=u.find('.//'+XBRLI+'unitNumerator/'+XBRLI+'measure'); den=u.find('.//'+XBRLI+'unitDenominator/'+XBRLI+'measure')
            units[u.get('id')]=(num.text.split(':')[-1] if num is not None else '?')+'/'+(den.text.split(':')[-1] if den is not None else '?')
    facts=[]
    for el in root:
        tag=el.tag
        if not tag.startswith('{') or el.get('contextRef') is None: continue
        uri,local=tag[1:].split('}')
        tax=ns.get(uri,'?')
        c=ctx.get(el.get('contextRef'))
        if c is None or c[2]>0: continue   # 축 붙은 팩트는 companyfacts가 안 싣는다
        if el.get('{http://www.w3.org/2001/XMLSchema-instance}nil')=='true' or el.text is None: continue
        try: val=Decimal(el.text.strip())
        except Exception: continue
        facts.append((tax, local, units.get(el.get('unitRef')), c[0], c[1], val))
    return facts
