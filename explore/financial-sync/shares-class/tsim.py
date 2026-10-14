import json,os,re,sys,collections,subprocess
D=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,D)
from rule2 import load,rule
TAUS=(0.036,0.074)
todo=json.load(open(os.path.join(D,'target_todo.json')))
forms={}
for t in set(x[1] for x in todo):
    fa=json.load(open(os.path.join(D,'tcf',t+'.json')))['facts']
    for node in fa.get('us-gaap',{}).values():
        for arr in node.get('units',{}).values():
            for x in arr: forms.setdefault(x['accn'],x.get('form',''))
q="select s.ticker, split_part(f.report_code,':',2), max(f.shares_outstanding) from financial_statement f join stock s on s.id=f.stock_id where s.market_code='US' group by 1,2"
stored={}
for l in subprocess.run(['psql','-d','stock_screener','-AF\t','-tc',q],capture_output=True,text=True,check=True).stdout.splitlines():
    t,a,s=l.split('\t'); stored[(t,a)]=float(s) if s else None
rows=collections.defaultdict(list)
for g,t,c,a,f in todo: rows[(g,t,c)].append((f,a))
out=open(os.path.join(D,'tsim.tsv'),'w')
for (g,t,c),fl in rows.items():
    prev=None; line=[]
    for f,a in sorted(fl):
        F,cover=load(os.path.join(D,'tinst',f'{c}_{a}.xml'))
        n,k,P=rule(t,F,cover,forms.get(a,'10-Q'))
        sh=cover.get('EntityCommonStockSharesOutstanding',{})
        cls=[float(v) for m,v in sh.items() if m!='-' and 'And' not in m.split(':')[-1] and float(v)>0 and not re.search(r'Preferred|Depositar|Note|Warrant',m,re.I)]
        sd=sum(cls) if cls else (float(sh['-']) if '-' in sh else None)
        r=sd/n if (n and sd) else None
        fl_=[('first' if prev is None else ('T' if abs(r/prev-1)>tau else '.')) if r else '-' for tau in TAUS]
        if r: prev=r
        old=stored.get((t,a))
        line.append((f,forms.get(a),k.split('/')[0],n,r,fl_,old))
        out.write('\t'.join(map(str,[g,t,f,a,forms.get(a),k,n,sd,r,*fl_,old]))+'\n')
    st=collections.Counter(x[2] for x in line)
    hid36=sum(1 for x in line if x[5][0] in('T','first')); hid74=sum(1 for x in line if x[5][1] in('T','first'))
    rs=[round(x[4],2) for x in line if x[4]]
    print(f"{g:10} {t:6} 공시{len(line):3} 상태{dict(st)} r[{min(rs) if rs else '-'}..{max(rs) if rs else '-'}] 숨김τ3.6%:{hid36} τ7.4%:{hid74}")
