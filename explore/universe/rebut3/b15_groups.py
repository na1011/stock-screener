# groups.tsv(운영 SELECT 산출 · CIK 다중 티커)에 새 이름 필터 + SIC 배제를 걸어 남는 다중 후보 그룹을 낸다.
# 목적: 거래대금 규칙 아래에서 보통주가 아닌 증권(우선·채권·예탁 등 이름 필터를 빠져나간 것)이 같은 CIK에 남는 그룹을 찾는다.
import re, collections
pat = re.compile(r"\bPREFERRED\b|\bPFD\b|\bWARRANTS?\b|\bRIGHTS?\b|\bDEPOSITARY\b|\bNOTES?\b|\bDEBENTURES?\b|\bBONDS?\b|\bETFS?\b|\bFUNDS?\b|\bCORPORATE UNITS?\b|\bEQUITY UNITS?\b|\bPREFERENCE\b|\bCAPITAL SECURITIES\b")
rows=[]
for line in open('/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/wt-explore/explore/universe/groups.tsv'):
    p=line.rstrip('\n').split('\t')
    if len(p)<6: continue
    cik,t,name,sic,sh,close=p[:6]; top=p[6] if len(p)>6 else ''
    rows.append((cik,t,name,sic,sh,close,top))
g=collections.defaultdict(list)
for r in rows:
    cik,t,name,sic,sh,close,top=r
    if sh in ('','\\N'): continue
    if sic in ('6798','6770'): continue
    if pat.search(name.upper()): continue
    g[cik].append(r)
multi={k:v for k,v in g.items() if len(v)>1}
print('multi-candidate groups', len(multi), 'tickers', sum(len(v) for v in multi.values()))
# 보통주로 보이지 않는 이름(의결권 클래스·보통주 표기 없음) 표시
common_kw=re.compile(r"COMMON|CLASS [A-Z]|CL [A-Z]|\bINC\b|CORP|ORDINARY|SHARES|COMPANY|HOLDINGS|LTD|PLC|N\.V\.|S\.A\.|GROUP|TRUST|LP\b|L\.P\.", re.I)
for k,v in sorted(multi.items(), key=lambda kv: -max(float(r[4] or 0)*float(r[5] or 0) if r[5] not in ('','\\N') else 0 for r in kv[1])):
    mc=[ (r[1], r[2][:60], r[5], (float(r[4])*float(r[5])/1e9 if r[5] not in ('','\\N') else None), 'MEMBER' if 'OBSERVED' in r[6] and r[6].rstrip().endswith('~') else '') for r in v]
    print(k, ' | '.join(f"{t}[{n}] c={c} mc={m:.2f}B {mem}" if m is not None else f"{t}[{n}] c=NA {mem}" for t,n,c,m,mem in mc))
