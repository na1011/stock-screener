import collections, sys
S='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/spin-dry'
SEP="$'\\t'"
def rows(p): return [x for x in (l.rstrip('\n').replace(SEP,'\t').split('\t') for l in open(p)) if len(x) >= 3]
# 예상 집합: b에서 새로 생긴 원장 행의 종목(분사 모체)
la={tuple(r[:3]) for r in rows(f'{S}/a/ledger.tsv')}
newb=[r for r in rows(f'{S}/b/ledger.tsv') if tuple(r[:3]) not in la]
expected={(r[0],r[1]) for r in newb}
spin_ex={}
for r in newb: spin_ex.setdefault((r[0],r[1]),[]).append(r[2])
# 수정계수 계단
def steps(side):
    d=collections.defaultdict(list)
    for r in rows(f'{S}/{side}/factors.tsv'): d[(r[0],r[1])].append(tuple(r[2:]))
    return d
fa,fb=steps('a'),steps('b')
chg_factor={k for k in set(fa)|set(fb) if sorted(fa.get(k,[]))!=sorted(fb.get(k,[]))}
print('factor-changed stocks:',len(chg_factor),'of which unexpected:',sorted(chg_factor-expected))
# (b) 바뀐 계단이 ex 앞에만 있는가: b의 마지막 계단(가장 늦은 구간)의 시작이 마지막 분사 ex 이상이어야 하고 그 계수가 a와 같아야 한다
bad_b=[]
for k in chg_factor & expected:
    a_last=max(fa[k], key=lambda t:t[1]); b_last=max(fb[k], key=lambda t:t[1])
    if a_last[2]!=b_last[2]: bad_b.append((k,'latest segment factor differs',a_last,b_last))
print('rule(b) violations:',len(bad_b)); [print('  ',x) for x in bad_b[:10]]
# 지표 지문
def ind(side):
    d={}
    for r in rows(f'{S}/{side}/indicators.tsv'): d[(r[0],r[2],r[3])]=(r[4],r[5])
    return d
ia,ib=ind('a'),ind('b')
keys=set(ia)|set(ib)
chg=[k for k in keys if ia.get(k)!=ib.get(k)]
bymkt=collections.Counter(k[0] for k in chg)
print('indicator (stock,code) cells changed by market:',dict(bymkt))
derived=lambda c: c.endswith('_PCTL') or 'SECTOR' in c
non_exp_raw=[k for k in chg if (k[0],k[1]) not in expected and not derived(k[2])]
print('rule(a) non-expected stocks with ORIGIN-layer change:',len(non_exp_raw)); 
c2=collections.Counter((k[0],k[1]) for k in non_exp_raw); print('  top:',c2.most_common(15))
print('rule(f) KR changes:',sum(1 for k in chg if k[0] in ('KOSPI','KOSDAQ')))
exp_chg=collections.Counter((k[0],k[1]) for k in chg if (k[0],k[1]) in expected)
print('expected parents with changes:',len(exp_chg),'/',len(expected),' parents w/o change:',sorted(expected-set(exp_chg)))
print('--- breakdown')
par=[k for k in chg if (k[0],k[1]) in expected]; oth=[k for k in chg if (k[0],k[1]) not in expected]
print('parent cells:',len(par),' other cells:',len(oth),' other codes:',collections.Counter(k[2] for k in oth).most_common(8))
# (h) 억제: 미해석 모체의 a·b 행 수
unres={(r[0],r[1]) for r in newb if r[3]=='UNKNOWN'}
for u in sorted(unres):
    ra=sum(int(v[0]) for k,v in ia.items() if (k[0],k[1])==u); rb=sum(int(v[0]) for k,v in ib.items() if (k[0],k[1])==u)
    ca=sum(1 for k in ia if (k[0],k[1])==u); cb=sum(1 for k in ib if (k[0],k[1])==u)
    print('  unresolved',u,'rows a',ra,'b',rb,'lost',ra-rb,' codes a',ca,'b',cb)
