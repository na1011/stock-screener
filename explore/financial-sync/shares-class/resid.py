# 폐합 잔차 분포: 축 없는 n·e 대 분자(A 꼴)를 전 공시에서. 잔차/|분자| 의 log10 히스토그램과 빈 구간.
import json,sys,collections,math,os
sys.path.insert(0,'.')
import rule3
from rule3 import load,ETAGS,WTAGS,NTAGS
todo=json.load(open('pop_todo.json')); H=collections.Counter(); ex=collections.defaultdict(list)
for g,t,c,a,f in todo:
    p=f'tinst/{c}_{a}.xml'
    if not os.path.exists(p): continue
    F,cover=load(p)
    for P in {p for tg in WTAGS for (m,p) in F[tg] if m=='-'}:
        n=next((F[tg][('-',P)] for tg in WTAGS if ('-',P) in F[tg]),None)
        e=next((F[tg][('-',P)] for tg in ETAGS if ('-',P) in F[tg]),None)
        num=next((F[tg][('-',P)] for tg in NTAGS if ('-',P) in F[tg]),None)
        if not n or not e or not num: continue
        bound=n*0.005+abs(e)*rule3._hw(WTAGS,'-',P)
        res=abs(n*e-num)
        if res<=bound: H['ok']+=1; continue
        r=res/abs(num); b=round(math.log10(r),1); H[b]+=1; ex[b].append((t,f,P[1],round(n*e/num,4)))
print('ok',H.pop('ok'))
for b in sorted(H): print(f'{b:5} {H[b]:4}',ex[b][:4])
