import json,os,re,collections
import xml.etree.ElementTree as ET
XI='http://www.xbrl.org/2003/instance'; XD='http://xbrl.org/2006/xbrldi'
stored={}
for l in open('stored.tsv'):
    t,s,p,m=l.rstrip('\n').split('\t'); stored[t]=(float(s) if s else None,p,m=='t')
fc={str(c):t for t,c,*r in json.load(open('fc.json'))}
tick=json.load(open('tick.json'))
bycik=collections.defaultdict(list)
for row in tick['data']:
    bycik[str(row[0])].append(row[2].replace('-','.'))
NONCOMMON=re.compile(r'Preferred|Depositar|Note|Debenture|Bond|Warrant|Right|Unit',re.I)
out=[]
for d in ('inst','inst3','inst4'):
    for f in os.listdir(d):
        c=f[:-4]
        try: r=ET.parse(f'{d}/{f}').getroot()
        except Exception: continue
        ctx={}
        for x in r.iter('{%s}context'%XI):
            dd={e.get('dimension'):e.text for e in x.iter('{%s}explicitMember'%XD)}
            per=x.find('{%s}period'%XI); ctx[x.get('id')]=(dd,tuple(y.text for y in per))
        sh={};w={};epsdim=set();epsnd=False
        for e in r:
            t=e.tag.split('}')[-1]; cx=ctx.get(e.get('contextRef'))
            if cx is None or not e.text: continue
            dd,per=cx
            if set(dd)-{'us-gaap:StatementClassOfStockAxis'}: continue
            k=dd.get('us-gaap:StatementClassOfStockAxis','-')
            if t=='EntityCommonStockSharesOutstanding': sh[k]=float(e.text)
            elif t=='WeightedAverageNumberOfSharesOutstandingBasic' and k=='-' and len(per)==2: w[per]=float(e.text)
            elif t=='EarningsPerShareBasic':
                if k=='-': epsnd=True
                else: epsdim.add(k)
        common={k:v for k,v in sh.items() if not NONCOMMON.search(k)}
        cls_={k:v for k,v in common.items() if k!='-'}
        N=sum(cls_.values()) if cls_ else common.get('-',0)
        both=('-' in common and bool(cls_))
        wl=w[max(w,key=lambda p:(p[1],p[0]))] if w else None
        tks=sorted(set(bycik.get(c,[]))&set(stored))
        for t in tks:
            s,p,mem=stored[t]
            out.append(dict(cik=c,t=t,member=mem,stored=s,N=N,ncls=len(cls_),both=both,und=common.get('-'),members=sorted(cls_),undim=('-' in sh),wavg=wl,epsdim=len(epsdim),epsnd=epsnd,
               rN=(s/N if s and N else None),rW=(s/wl if s and wl else None)))
json.dump(out,open('cmp.json','w'),indent=0)
mem=[o for o in out if o['member']]
def cls(o):
    if o['stored'] is None: return 'stored NULL'
    if not o['N']: return 'no dei'
    r=o['rN']
    return 'within3%' if abs(r-1)<=0.03 else ('3-10%' if abs(r-1)<=0.10 else 'off>10%')
print('current-member lines compared',len(mem),collections.Counter(cls(o) for o in mem))
print('multi-class (>=2 common dei) member lines',len([o for o in mem if o['ncls']>=2]),collections.Counter(cls(o) for o in mem if o['ncls']>=2))
for o in sorted(mem,key=lambda o:-(abs((o['rN'] or 1)-1))):
    if cls(o) in('off>10%','3-10%','stored NULL'):
        print(o['t'],cls(o),f"stored {o['stored'] and o['stored']/1e6:.1f}M" if o['stored'] else 'stored -',f"N {o['N']/1e6:.1f}M ncls {o['ncls']} wavg {o['wavg'] and round(o['wavg']/1e6,1)} epsdim {o['epsdim']}")
