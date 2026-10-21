import re
NUM={'zero':0,'one':1,'two':2,'three':3,'four':4,'five':5,'six':6,'seven':7,'eight':8,'nine':9,'ten':10,'eleven':11,'twelve':12,'thirteen':13,'fourteen':14,'fifteen':15,'sixteen':16,'seventeen':17,'eighteen':18,'nineteen':19,'twenty':20,'thirty':30,'forty':40,'fifty':50,'sixty':60,'seventy':70,'eighty':80,'ninety':90}
def words(s):
    """'two hundred and forty' · 'twenty-three' · '1,000' · '0.4' → number, or None"""
    s=s.strip().replace(',','')
    try: return float(s)
    except ValueError: pass
    total=cur=0; seen=False
    for w in re.split(r'[\s\-]+',s):
        if w in ('and',''): continue
        if w in NUM: cur+=NUM[w]; seen=True
        elif w=='hundred': cur=(cur or 1)*100; seen=True
        elif w=='thousand': total+=(cur or 1)*1000; cur=0; seen=True
        else: return None
    return float(total+cur) if seen else None
Q=r'((?:[\d][\d,.]*)|(?:(?:a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|and)[\s\-]*)+)\s*(?:\((\d[\d,.]*)\))?'
UNIT=r'(?:class\s+[a-z]\s+)?(?:ordinary|common|equity)\s+shares?'
ADS=r'(?:american\s+depositary\s+(?:shares?|receipts?)|ads[s]?|adrs?)'
def norm(t): return re.sub(r'\s+',' ',t.replace('\xa0',' ')).lower()
def ratio(title):
    """ordinary shares per 1 ADS, or None"""
    s=norm(title)
    if not re.search(r'depositar|depositor|\bads\b|\badss\b|\badr',s): return None
    s=re.sub(r'(representing|represents|represented|represent(?!s|ing|ed)|receive)(?=[a-z0-9(])',r'\1 ',s)
    s=re.sub(r'(\d)(?=[a-z])',r'\1 ',s)   # '15class'   # 'representingone' · 'representingthree'
    # N ADSs represent M ordinary shares
    m=re.search(r'(?:each|every)\s+(?!represent)'+Q+r'\s*(?:'+ADS+r')?\s*(?:\w+\s+){0,3}?represent\w*\s+'+Q+r'\s*'+UNIT,s)
    if m:
        a=float(m.group(2).replace(',','')) if m.group(2) else words(m.group(1))
        b=float(m.group(4).replace(',','')) if m.group(4) else words(m.group(3))
        if a and b is not None: return b/a
    m=re.search(r'(?:represent\w*|receive)\s+(?:the\s+right\s+to\s+receive\s+)?'+Q+r'\s*'+UNIT,s)
    if m:
        v=float(m.group(2).replace(',','')) if m.group(2) else (1.0 if m.group(1).strip()=='a' else words(m.group(1)))
        return v
    return None
