# 0콜: 저장 인스턴스 15개에서 「자기 CIK 컨텍스트 수」·dei:DocumentType·submissions form을 대조
import glob, json, os, re, sys
import xml.etree.ElementTree as ET
GA='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga'
XBRLI='http://www.xbrl.org/2003/instance'
def form_of(d, cik, accn_compact):
    p=f'{d}/subs-{cik}.json'
    if not os.path.exists(p): return None
    s=json.load(open(p))
    def scan(r):
        for a,f,fd,rd in zip(r['accessionNumber'],r['form'],r['filingDate'],r.get('reportDate',['']*len(r['form']))):
            if a.replace('-','')==accn_compact: return (f,fd,rd)
    r=scan(s['filings']['recent'])
    return r
for d in ('out2','out3'):
    for inst in sorted(glob.glob(f'{GA}/{d}/inst-*.xml')):
        m=re.match(r'inst-(\d+)-(\d+)\.xml',os.path.basename(inst)); cik,acc=m.group(1),m.group(2)
        frm=form_of(f'{GA}/{d}',cik,acc)
        tree=ET.parse(inst); root=tree.getroot()
        ctx={}; ids=set(); own=0; own_noaxis=0
        for c in root.iter(f'{{{XBRLI}}}context'):
            ident=c.find(f'{{{XBRLI}}}entity/{{{XBRLI}}}identifier')
            idv=ident.text.strip() if ident is not None else None
            seg=c.find(f'{{{XBRLI}}}entity/{{{XBRLI}}}segment')
            scen=c.find(f'{{{XBRLI}}}scenario')
            axis = (seg is not None and len(list(seg))>0) or (scen is not None and len(list(scen))>0)
            ctx[c.get('id')]=(idv,axis)
            ids.add((ident.get('scheme') if ident is not None else None, idv))
            if idv is not None and idv.lstrip('0')==cik.lstrip('0'):
                own+=1
                if not axis: own_noaxis+=1
        dts=[]; amend=[]
        for el in root:
            tag=el.tag
            if tag.endswith('}DocumentType'): dts.append((el.text, ctx.get(el.get('contextRef'),(None,None))))
            if tag.endswith('}AmendmentFlag'): amend.append(el.text)
        ns_dei=[t for t in {el.tag.split('}')[0][1:] for el in root if el.tag.endswith('}DocumentType')}]
        print(f"{d} cik={cik} accn={acc} form={frm} contexts={len(ctx)} own={own} own_noaxis={own_noaxis} ids={sorted(ids)[:3]} DocumentType={dts} AmendmentFlag={amend} dei_ns={ns_dei}")
