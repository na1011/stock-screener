# 보존 창 공시마다 「시점 참여」를 원장이 말하는 층을 센다: a 구성원 EPS · b 축 없는 EPS + 구성원 분모 · c 둘 다 없음
# 그리고 참여 구성원이 표지 구성원에 다 있는가(이름 어긋남)
import json,sys,collections
sys.path.insert(0,'.')
from rule3 import load,rule,ETAGS,WTAGS
todo=json.load(open('pop_todo.json'))
src=open('v2q2.py').read(); ns={'__file__':'v2q2.py'}
exec(src[:src.index('by=collections')].replace("todo=json.load(open(os.path.join(D,sys.argv[1]))); W=sys.argv[2]; TAU=float(sys.argv[3]); VERB=set(sys.argv[4].split(',')) if len(sys.argv)>4 else set()",""),ns)
W='2025-10-08'; C=collections.Counter(); miss=collections.defaultdict(list); lay=collections.defaultdict(set); ex=collections.defaultdict(list)
for t in sorted({x[1] for x in todo}):
    forms,*_=ns['cfdata'](t)
    an=sorted(fd for a,(fm,fd) in forms.items() if fm in('10-K','10-Q','20-F','40-F'))
    b=[d for d in an if d<=W]; w0=max(b) if b else W
    for g,tt,c,a,f in todo:
        if tt!=t or f<w0: continue
        F,cover=load(f'tinst/{c}_{a}.xml'); form=forms.get(a,('10-Q',))[0]
        n,k,P=rule(t,F,cover,form)
        if not P or n is None: C['rule-fail']+=1; continue
        em={m for tg in ETAGS for (m,p) in F[tg] if p==P and m!='-'}
        wm={m for tg in WTAGS for (m,p) in F[tg] if p==P and m!='-'}
        cv={m for m,v in cover.get('EntityCommonStockSharesOutstanding',{}).items() if m!='-' and float(v)>0}
        L='a' if em else ('b' if wm else 'c'); C[L]+=1; lay[t].add(L)
        S=em or wm
        if S and not S<=cv: miss[t].append((f,sorted(x.split(':')[-1] for x in S-cv),sorted(x.split(':')[-1] for x in cv)))
        if L=='c': ex[t].append((f,round(n/1e6,1),{m.split(':')[-1]:round(float(v)/1e6,1) for m,v in cover.get('EntityCommonStockSharesOutstanding',{}).items()}))
print(C); print('발행사 층',collections.Counter(''.join(sorted(v)) for v in lay.values()))
print('참여 구성원이 표지에 없음:',len(miss))
for t,v in miss.items(): print('  ',t,v[0],len(v))
json.dump(ex,open('sig_c.json','w'))
print('c층 발행사',len(ex))
